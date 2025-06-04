package com.wireless.earbuds;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class WalkieTalkieHandler extends AbstractWebSocketHandler {
    private static final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private static volatile WebSocketSession broadcaster = null;
    private static final Map<String, String> clientIds = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastBroadcastTimes = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        String clientId = "Client-" + session.getId().substring(0, 5);
        clientIds.put(session.getId(), clientId);
        session.sendMessage(new TextMessage("ID:" + clientId));
        System.out.println("Client connected: " + clientId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        System.out.println("Received text message: " + payload + " from " + clientIds.get(session.getId()));
        
        if (payload.startsWith("START")) {
            // Prevent rapid broadcaster switching
            long now = System.currentTimeMillis();
            Long lastBroadcast = lastBroadcastTimes.get(session.getId());
            
            if (lastBroadcast != null && (now - lastBroadcast) < 2000) {
                session.sendMessage(new TextMessage("WAIT"));
                return;
            }
            
            if (broadcaster == null) {
                broadcaster = session;
                lastBroadcastTimes.put(session.getId(), now);
                String clientId = clientIds.get(session.getId());
                broadcastMessage("BROADCAST_START:" + clientId, session);
                System.out.println(clientId + " started broadcasting");
            } else {
                session.sendMessage(new TextMessage("BUSY"));
                System.out.println("Rejected broadcast request from " + clientIds.get(session.getId()) + " - channel busy");
            }
        } else if ("STOP".equals(payload)) {
            if (session.equals(broadcaster)) {
                broadcaster = null;
                broadcastMessage("BROADCAST_STOP", session);
                System.out.println("Broadcasting stopped by " + clientIds.get(session.getId()));
            }
        } else if ("READY".equals(payload)) {
            session.sendMessage(new TextMessage("STATUS:Connected"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        if (session.equals(broadcaster)) {
            ByteBuffer payload = message.getPayload();
            int size = payload.remaining();
            System.out.println("Broadcasting audio packet: " + size + " bytes");
            
            // Create a copy of the buffer for each client
            for (WebSocketSession client : sessions) {
                if (client.isOpen()) {
                    try {
                        // Create a new buffer for each client to avoid concurrent access issues
                        ByteBuffer copy = ByteBuffer.allocate(size);
                        copy.put(payload.duplicate());
                        copy.flip();
                        client.sendMessage(new BinaryMessage(copy));
                    } catch (Exception e) {
                        System.err.println("Error sending audio to " + clientIds.get(client.getId()) + ": " + e.getMessage());
                        // Remove disconnected clients
                        sessions.remove(client);
                    }
                }
            }
        }
    }

    private void broadcastMessage(String message, WebSocketSession exclude) throws IOException {
        for (WebSocketSession client : sessions) {
            if (!client.equals(exclude) && client.isOpen()) {
                try {
                    client.sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    System.err.println("Error sending message to " + clientIds.get(client.getId()) + ": " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("Client disconnected: " + clientIds.get(session.getId()));
        sessions.remove(session);
        clientIds.remove(session.getId());
        lastBroadcastTimes.remove(session.getId());
        
        if (session.equals(broadcaster)) {
            broadcaster = null;
            try {
                broadcastMessage("BROADCAST_STOP", session);
            } catch (IOException e) {
                System.err.println("Error sending broadcast stop: " + e.getMessage());
            }
        }
    }
}