package com.wireless.earbuds;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class WalkieTalkieHandler extends AbstractWebSocketHandler {
    private static final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private static WebSocketSession broadcaster = null;
    private static final Map<String, String> clientIds = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        String clientId = "Client-" + session.getId().substring(0, 5);
        clientIds.put(session.getId(), clientId);
        session.sendMessage(new TextMessage("ID:" + clientId));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload.startsWith("START")) {
            if (broadcaster == null) {
                broadcaster = session;
                String clientId = clientIds.get(session.getId());
                broadcastMessage("BROADCAST_START:" + clientId, session);
            } else {
                session.sendMessage(new TextMessage("BUSY"));
            }
        } else if ("STOP".equals(payload) && session.equals(broadcaster)) {
            broadcaster = null;
            broadcastMessage("BROADCAST_STOP", session);
        } else if ("READY".equals(payload)) {
            // Handle ready message
            session.sendMessage(new TextMessage("STATUS:Connected"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        if (session.equals(broadcaster)) {
            for (WebSocketSession client : sessions) {
                if (!client.equals(session) && client.isOpen()) {
                    client.sendMessage(message);
                }
            }
        }
    }

    private void broadcastMessage(String message, WebSocketSession exclude) throws IOException {
        for (WebSocketSession client : sessions) {
            if (client != exclude && client.isOpen()) {
                client.sendMessage(new TextMessage(message));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        clientIds.remove(session.getId());
        if (session.equals(broadcaster)) {
            broadcaster = null;
            try {
                broadcastMessage("BROADCAST_STOP", session);
            } catch (IOException e) {
                // Log error
            }
        }
    }
}