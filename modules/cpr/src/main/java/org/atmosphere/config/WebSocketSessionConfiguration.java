package org.atmosphere.config;

import jakarta.websocket.Session;

public class WebSocketSessionConfiguration {
    private final Session session;
    private final int maxBinaryBufferSize;
    private final int webSocketIdleTimeoutMs;
    private final int maxTextBufferSize;

    public WebSocketSessionConfiguration(Session session, int maxBinaryBufferSize, int webSocketIdleTimeoutMs, int maxTextBufferSize) {
        this.session = session;
        this.maxBinaryBufferSize = maxBinaryBufferSize;
        this.webSocketIdleTimeoutMs = webSocketIdleTimeoutMs;
        this.maxTextBufferSize = maxTextBufferSize;
    }

    public void configure() {
        if (maxBinaryBufferSize != -1) {
            session.setMaxBinaryMessageBufferSize(maxBinaryBufferSize);
        }
        if (webSocketIdleTimeoutMs != -1) {
            session.setMaxIdleTimeout(webSocketIdleTimeoutMs);
        }
        if (maxTextBufferSize != -1) {
            session.setMaxTextMessageBufferSize(maxTextBufferSize);
        }
    }
}