package com.thughari.collabeditor.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thughari.collabeditor.service.WebSocketService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
@Slf4j
public class EditorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketService collaborationService;

    public EditorWebSocketHandler(ObjectMapper objectMapper, WebSocketService collaborationService) {
        this.objectMapper = objectMapper;
        this.collaborationService = collaborationService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String docId = collaborationService.getDocIdFromUri(session.getUri().getPath());
        if (docId == null) {
            log.warn("Connection attempt with invalid URI path: {}", session.getUri().getPath());
            session.close(CloseStatus.BAD_DATA.withReason("Invalid document ID in URI"));
            return;
        }

        collaborationService.registerSession(session, docId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode rootNode = objectMapper.readTree(message.getPayload());
            String type = rootNode.path("type").asText();
            JsonNode payload = rootNode.path("payload");

            String docId = collaborationService.getDocIdForSession(session);
            if (docId == null) {
                log.warn("Message received for session {} which has no document ID associated. Type: {}. Payload: {}",
                         session.getId(), type, payload.toString());
                collaborationService.sendErrorMessage(session, "Session not properly initialized with a document ID.");
                return;
            }
            
            String docIdFromPayload = payload.path("documentId").asText(null);
            if (docIdFromPayload != null && !docId.equals(docIdFromPayload)) {
                 log.warn("Document ID mismatch for session {}. Session DocId: {}, Payload DocId: {}. Message type: {}",
                         session.getId(), docId, docIdFromPayload, type);
                collaborationService.sendErrorMessage(session, "Document ID mismatch in message payload.");
                return;
            }


            String username = collaborationService.getUsernameForSession(session);

            switch (type) {
                case "join":
                    String joinUsername = payload.path("username").asText(null);
                    try {
                        collaborationService.handleJoinMessage(session, docId, joinUsername);
                    } catch (IllegalArgumentException e) {
                        session.close(CloseStatus.BAD_DATA.withReason(e.getMessage()));
                    }
                    break;
                case "edit":
                    if (username == null) {
                        log.warn("Edit attempt from session {} without established username for doc {}", session.getId(), docId);
                        collaborationService.sendErrorMessage(session, "Cannot edit: user not properly joined. Please send 'join' message first.");
                        return;
                    }
                    String content = payload.path("content").asText();
                    collaborationService.handleEditMessage(session, docId, content, username);
                    break;
                case "comment":
                     if (username == null) {
                        log.warn("Comment attempt from session {} without established username for doc {}", session.getId(), docId);
                        collaborationService.sendErrorMessage(session, "Cannot comment: user not properly joined. Please send 'join' message first.");
                        return;
                    }
                    JsonNode commentNode = payload.path("comment");
                    collaborationService.handleCommentMessage(session, docId, commentNode, username);
                    break;
                default:
                    log.warn("Received unknown message type: {} from session {} for doc {}", type, session.getId(), docId);
                    collaborationService.sendErrorMessage(session, "Unknown message type: " + type);
            }
        } catch (JsonProcessingException e) {
            log.error("Error processing JSON message from session {}: {}", session.getId(), message.getPayload(), e);
            if (session.isOpen()) {
               collaborationService.sendErrorMessage(session, "Invalid JSON format.");
            }
        } catch (IOException e) {
            log.error("IO Error handling text message from session {}: {}", session.getId(), message.getPayload(), e);
             if (session.isOpen()) {
                collaborationService.sendErrorMessage(session, "Server error processing your request due to IO issue.");
            }
        } 
        catch (Exception e) {
            log.error("Unexpected error handling text message from session {}: {}", session.getId(), message.getPayload(), e);
            if (session.isOpen()) {
               collaborationService.sendErrorMessage(session, "Server error processing your request.");
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        WebSocketService.SessionDetails details = collaborationService.unregisterSession(session);

        log.info("Session {} (User: {}) disconnected. Reason: {}. Doc ID: {}",
                session.getId(),
                details.getUsername() != null ? details.getUsername() : "N/A",
                status,
                details.getDocId() != null ? details.getDocId() : "N/A");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error for session {}: {}", session.getId(), exception.getMessage(), exception);
        super.handleTransportError(session, exception);
    }
}