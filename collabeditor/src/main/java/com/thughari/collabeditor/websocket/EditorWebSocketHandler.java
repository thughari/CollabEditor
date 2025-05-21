package com.thughari.collabeditor.websocket;


import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thughari.collabeditor.model.DocumentEntity;
import com.thughari.collabeditor.repo.DocumentRepository;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class EditorWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> docSessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> sessionToDoc = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DocumentRepository documentRepository;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String path = session.getUri().getPath();
        String docId = path.substring(path.lastIndexOf("/") + 1);

        docSessions.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionToDoc.put(session, docId);

        DocumentEntity doc = documentRepository.findById(docId).orElse(new DocumentEntity(docId));
        session.sendMessage(new TextMessage(doc.getContent()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(message.getPayload());

        String documentId = jsonNode.get("documentId").asText();
        String content = jsonNode.get("content").asText();

        DocumentEntity doc = documentRepository.findById(documentId).orElse(new DocumentEntity(documentId));
        doc.setContent(content);
        documentRepository.save(doc);

        Set<WebSocketSession> sessions = docSessions.get(documentId);
        if (sessions != null) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen() && !s.equals(session)) {
                    s.sendMessage(new TextMessage(content));
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Connection closed. Reason: {}", status.getReason());

        String docId = sessionToDoc.remove(session);
        if (docId != null) {
            Set<WebSocketSession> sessions = docSessions.get(docId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    docSessions.remove(docId);
                }
            }
        }
    }
}
