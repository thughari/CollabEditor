package com.thughari.collabeditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thughari.collabeditor.model.DocumentEntity;
import com.thughari.collabeditor.repo.DocumentRepository;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WebSocketService {

    private final ObjectMapper objectMapper;
    private final DocumentRepository documentRepository;

    private final Map<String, Set<WebSocketSession>> docSessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> sessionToDocId = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> sessionToUsername = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> docComments = new ConcurrentHashMap<>();

    public WebSocketService(ObjectMapper objectMapper, DocumentRepository documentRepository) {
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
    }

    @Getter
    public static class SessionDetails {
        private final String docId;
        private final String username;

        public SessionDetails(String docId, String username) {
            this.docId = docId;
            this.username = username;
        }
    }

    public String getDocIdFromUri(String path) {
        if (path == null || !path.contains("/ws/editor/")) {
            return null;
        }
        int lastSlash = path.lastIndexOf("/");
        if (lastSlash == path.length() - 1) {
            log.warn("Document ID is missing from URI path: {}", path);
            return null;
        }
        return path.substring(lastSlash + 1);
    }

    public void registerSession(WebSocketSession session, String docId) {
        sessionToDocId.put(session, docId);
        docSessions.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("Session {} registered for document path: {}. Awaiting 'join' message.", session.getId(), docId);
    }

    public SessionDetails unregisterSession(WebSocketSession session) {
        String docId = sessionToDocId.remove(session);
        String username = sessionToUsername.remove(session);

        if (docId != null) {
            Set<WebSocketSession> sessionsInDoc = docSessions.get(docId);
            if (sessionsInDoc != null) {
                sessionsInDoc.remove(session);
                if (sessionsInDoc.isEmpty()) {
                    docSessions.remove(docId);
                     docComments.remove(docId); 
                    log.info("All sessions closed for document {}. It is now inactive.", docId);
                } else {
                    try {
                        broadcastCollaboratorsUpdate(docId);
                    } catch (IOException e) {
                        log.error("Error broadcasting collaborator update for doc {} after session {} closed: {}", docId, session.getId(), e.getMessage());
                    }
                }
            }
        }
        return new SessionDetails(docId, username);
    }

    public String getDocIdForSession(WebSocketSession session) {
        return sessionToDocId.get(session);
    }

    public String getUsernameForSession(WebSocketSession session) {
        return sessionToUsername.get(session);
    }

    public void handleJoinMessage(WebSocketSession session, String docId, String username) throws IOException {
        if (username == null || username.trim().isEmpty()) {
            log.warn("Join attempt with no username from session {} for doc {}", session.getId(), docId);
            sendErrorMessage(session, "Username is required to join.");
            throw new IllegalArgumentException("Username is required to join.");
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

    public void handleEditMessage(WebSocketSession session, String docId, String content, String username) throws IOException {
        if (username == null) {
            log.warn("Edit attempt from session {} without established username for doc {}", session.getId(), docId);
            sendErrorMessage(session, "Cannot edit: user not properly joined.");
            return;
        }

        DocumentEntity doc = documentRepository.findById(docId).orElseGet(() -> {
            log.warn("Document {} not found during edit by user {}. Creating it.", docId, username);
            return new DocumentEntity(docId);
        });
        doc.setContent(content);
        documentRepository.save(doc);

        Map<String, Object> contentUpdatePayload = Map.of("content", content, "editor", username);
        broadcastMessageToOthers(docId, session, "content_update", contentUpdatePayload);
        log.debug("Document {} updated by '{}'. Broadcasting.", docId, username);
    }

    public void handleCommentMessage(WebSocketSession session, String docId, JsonNode commentNode, String username) throws IOException {
         if (username == null) {
            log.warn("Comment attempt from session {} without established username for doc {}", session.getId(), docId);
            sendErrorMessage(session, "Cannot comment: user not properly joined.");
            return;
        }

        String commentText = commentNode.path("comment").asText();
        String commentUser = username; // Or: commentNode.path("user").asText(username);

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

    public List<String> getCurrentCollaborators(String docId) {
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

    public void broadcastCollaboratorsUpdate(String docId) throws IOException {
        List<String> collaborators = getCurrentCollaborators(docId);
        broadcastMessageToAll(docId, "collaborators_update", Map.of("collaborators", collaborators));
        log.debug("Broadcasting collaborators update for doc {}: {}", docId, collaborators);
    }

    public void sendMessageToSession(WebSocketSession session, String type, Object payload) throws IOException {
        if (session.isOpen()) {
            Map<String, Object> messageMap = Map.of("type", type, "payload", payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(messageMap)));
            }
        } else {
            log.warn("Attempted to send message to closed session {}: type {}, payload {}", session.getId(), type, payload);
        }
    }

    public void sendErrorMessage(WebSocketSession session, String errorMessage) throws IOException {
        sendMessageToSession(session, "error", Map.of("message", errorMessage));
    }

    public void broadcastMessageToAll(String docId, String type, Object payload) throws IOException {
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

    public void broadcastMessageToOthers(String docId, WebSocketSession sender, String type, Object payload) throws IOException {
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