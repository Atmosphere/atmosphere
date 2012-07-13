package org.atmosphere.cpr;

import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;

public class WebSocketProcessorFactory {

    private static WebSocketProcessorFactory factory;
    private final AtmosphereConfig config;
    private final String webSocketProcessorName;

    protected WebSocketProcessorFactory(AtmosphereConfig config) {
        this.config = config;
        factory = this;
        webSocketProcessorName = config.framework().getWebSocketProcessorClassName();
    }

    public final static WebSocketProcessorFactory getDefault() {
        return factory;
    }

    public WebSocketProcessor newWebSocketProcessor(WebSocket webSocket) {
        WebSocketProcessor wp = null;
        if (!webSocketProcessorName.equalsIgnoreCase(WebSocketProcessor.class.getName())) {
            try {
                wp = (WebSocketProcessor) Thread.currentThread().getContextClassLoader()
                        .loadClass(webSocketProcessorName).newInstance();
            } catch (Exception ex) {
                try {
                    wp = (WebSocketProcessor) getClass().getClassLoader()
                            .loadClass(webSocketProcessorName).newInstance();
                } catch (Exception ex2) {
                }
            }
        }

        if (wp == null) {
            wp = new DefaultWebSocketProcessor(config.framework(), webSocket, config.framework().getWebSocketProtocol());
        }

        return wp;
    }

}
