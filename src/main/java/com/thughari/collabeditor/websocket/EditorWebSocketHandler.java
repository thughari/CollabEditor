package com.thughari.collabeditor.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thughari.collabeditor.model.DocumentEntity;
import com.thughari.collabeditor.repo.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;


@Component
@Slf4j
public class EditorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Set<WebSocketSession>> docSessions = new ConcurrentHashMap<>();
    
    private final Map<WebSocketSession, String> sessionToDocId = new ConcurrentHashMap<>();
    
    private final Map<WebSocketSession, String> sessionToUsername = new ConcurrentHashMap<>();
    
    private final Map<String, List<Map<String, Object>>> docComments = new ConcurrentHashMap<>();

    @Autowired
    private DocumentRepository documentRepository;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String docId = getDocIdFromUri(session.getUri().getPath());
        if (docId == null) {
            log.warn("Connection attempt with invalid URI path: {}", session.getUri().getPath());
            session.close(CloseStatus.BAD_DATA.withReason("Invalid document ID in URI"));
            return;
        }

        sessionToDocId.put(session, docId);
        docSessions.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);

        log.info("Session {} connected for document path: {}. Awaiting 'join' message.", session.getId(), docId);
        
    }

    private String getDocIdFromUri(String path) {
        if (path == null || !path.contains("/ws/editor/")) {
            return null;
        }

        int lastSlash = path.lastIndexOf("/");
        if (lastSlash == path.length() -1) {
            log.warn("Document ID is missing from URI path: {}", path);
            return null;
        }
        return path.substring(lastSlash + 1);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode rootNode = objectMapper.readTree(message.getPayload());
            String type = rootNode.path("type").asText();
            JsonNode payload = rootNode.path("payload");

            String docIdFromPayload = payload.path("documentId").asText(null);
            String docIdFromSession = sessionToDocId.get(session);

            String effectiveDocId = (docIdFromSession != null) ? docIdFromSession : docIdFromPayload;

            if (effectiveDocId == null) {
                log.warn("Message received without documentId (session: {}, payload: {}). Type: {}", session.getId(), payload.toString(), type);
                sendErrorMessage(session, "Document ID is missing.");
                return;
            }

            if (!"join".equals(type) && docIdFromPayload != null && !effectiveDocId.equals(docIdFromPayload)) {
                log.warn("Document ID mismatch. Session: {}, Payload: {}. Session DocId: {}, Payload DocId: {}",
                         session.getId(), payload.toString(), effectiveDocId, docIdFromPayload);
                sendErrorMessage(session, "Document ID mismatch.");
                return;
            }


            switch (type) {
                case "join":
                    handleJoin(session, payload, effectiveDocId);
                    break;
                case "edit":
                    handleEdit(session, payload, effectiveDocId);
                    break;
                case "comment":
                    handleComment(session, payload, effectiveDocId);
                    break;
                default:
                    log.warn("Received unknown message type: {} from session {} for doc {}", type, session.getId(), effectiveDocId);
                    sendErrorMessage(session, "Unknown message type: " + type);
            }
        } catch (JsonProcessingException e) {
            log.error("Error processing JSON message from session {}: {}", session.getId(), message.getPayload(), e);
            sendErrorMessage(session, "Invalid JSON format.");
        } catch (Exception e) {
            log.error("Error handling text message from session {}: {}", session.getId(), message.getPayload(), e);
            sendErrorMessage(session, "Server error processing your request.");
        }
    }

    private void handleJoin(WebSocketSession session, JsonNode payload, String docId) throws IOException {
        String username = payload.path("username").asText(null);
        if (username == null || username.trim().isEmpty()) {
            log.warn("Join attempt with no username from session {} for doc {}", session.getId(), docId);
            sendErrorMessage(session, "Username is required to join.");
            session.close(CloseStatus.BAD_DATA.withReason("Username required"));
            return;
        }

        sessionToUsername.put(session, username);

        log.info("User '{}' (session {}) joined document '{}'", username, session.getId(), docId);

        DocumentEntity doc = documentRepository.findById(docId).orElseGet(() -> {
            log.info("Document {} not found, creating new.", docId);
            return documentRepository.save(new DocumentEntity(docId));
        });

        Map<String, Object> initialDataPayload = new HashMap<>();
        initialDataPayload.put("content", doc.getContent() != null ? doc.getContent() : "");
        initialDataPayload.put("collaborators", getCurrentCollaborators(docId));
        initialDataPayload.put("comments", docComments.getOrDefault(docId, new ArrayList<>()));
        
        sendMessageToSession(session, "initial_data", initialDataPayload);

        broadcastCollaboratorsUpdate(docId);
    }

    private void handleEdit(WebSocketSession session, JsonNode payload, String docId) throws IOException {
        String username = sessionToUsername.get(session);
        if (username == null) {
            log.warn("Edit attempt from session {} without established username for doc {}", session.getId(), docId);
            sendErrorMessage(session, "Cannot edit: user not properly joined.");
            return;
        }

        String content = payload.path("content").asText();

        DocumentEntity doc = documentRepository.findById(docId).orElseGet(() -> new DocumentEntity(docId));
        doc.setContent(content);
        documentRepository.save(doc);

        Map<String, Object> contentUpdatePayload = Map.of("content", content, "editor", username);
        broadcastMessageToOthers(docId, session, "content_update", contentUpdatePayload);
        log.debug("Document {} updated by '{}'. Broadcasting.", docId, username);
    }

    private void handleComment(WebSocketSession session, JsonNode payload, String docId) throws IOException {
        String username = sessionToUsername.get(session);
         if (username == null) {
            log.warn("Comment attempt from session {} without established username for doc {}", session.getId(), docId);
            sendErrorMessage(session, "Cannot comment: user not properly joined.");
            return;
        }

        JsonNode commentNode = payload.path("comment");
        String commentText = commentNode.path("comment").asText();
        String commentUser = commentNode.path("user").asText(username);

        if (commentText.trim().isEmpty()) {
            log.warn("Empty comment received from {} for doc {}", username, docId);
            return;
        }

        Map<String, Object> newComment = new HashMap<>();
        newComment.put("user", commentUser);
        newComment.put("comment", commentText);
        newComment.put("timestamp", System.currentTimeMillis());

        docComments.computeIfAbsent(docId, k -> new CopyOnWriteArrayList<>()).add(newComment);

        broadcastMessageToAll(docId, "new_comment", Map.of("comment", newComment));
        log.info("User '{}' added comment to document '{}': {}", commentUser, docId, commentText);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String docId = sessionToDocId.remove(session);
        String username = sessionToUsername.remove(session);

        log.info("Session {} (User: {}) disconnected. Reason: {}. Doc ID: {}",
                session.getId(), username != null ? username : "N/A", status, docId != null ? docId : "N/A");

        if (docId != null) {
            Set<WebSocketSession> sessionsInDoc = docSessions.get(docId);
            if (sessionsInDoc != null) {
                sessionsInDoc.remove(session);
                if (sessionsInDoc.isEmpty()) {
                    docSessions.remove(docId);
                    log.info("All sessions closed for document {}. It is now inactive.", docId);
                } else {
                    broadcastCollaboratorsUpdate(docId);
                }
            }
        }
    }

    private List<String> getCurrentCollaborators(String docId) {
        Set<WebSocketSession> sessions = docSessions.get(docId);
        if (sessions == null) {
            return new ArrayList<>();
        }
        return sessions.stream()
                .map(s -> sessionToUsername.get(s))
                .filter(name -> name != null && !name.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private void broadcastCollaboratorsUpdate(String docId) throws IOException {
        List<String> collaborators = getCurrentCollaborators(docId);
        broadcastMessageToAll(docId, "collaborators_update", Map.of("collaborators", collaborators));
        log.debug("Broadcasting collaborators update for doc {}: {}", docId, collaborators);
    }
    
    private void sendMessageToSession(WebSocketSession session, String type, Object payload) throws IOException {
        if (session.isOpen()) {
            Map<String, Object> messageMap = Map.of("type", type, "payload", payload);
            synchronized (session) {
                 session.sendMessage(new TextMessage(objectMapper.writeValueAsString(messageMap)));
            }
        }
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) throws IOException {
        sendMessageToSession(session, "error", Map.of("message", errorMessage));
    }
    
    private void broadcastMessageToAll(String docId, String type, Object payload) throws IOException {
        Set<WebSocketSession> sessions = docSessions.get(docId);
        if (sessions != null) {
            Map<String, Object> messageMap = Map.of("type", type, "payload", payload);
            String messageString = objectMapper.writeValueAsString(messageMap);
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(messageString));
                    }
                }
            }
        }
    }

    private void broadcastMessageToOthers(String docId, WebSocketSession sender, String type, Object payload) throws IOException {
        Set<WebSocketSession> sessions = docSessions.get(docId);
        if (sessions != null) {
            Map<String, Object> messageMap = Map.of("type", type, "payload", payload);
            String messageString = objectMapper.writeValueAsString(messageMap);
            for (WebSocketSession s : sessions) {
                if (s.isOpen() && !s.equals(sender)) {
                     synchronized (s) {
                        s.sendMessage(new TextMessage(messageString));
                    }
                }
            }
        }
    }
}