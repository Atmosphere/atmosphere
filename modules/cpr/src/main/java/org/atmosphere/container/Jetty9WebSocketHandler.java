package org.atmosphere.container;

import org.atmosphere.container.version.Jetty9WebSocket;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

public class Jetty9WebSocketHandler implements WebSocketListener {

    private static final Logger logger = LoggerFactory.getLogger(Jetty9WebSocketHandler.class);

    private final AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private final WebSocketProcessor webSocketProcessor;
    private WebSocket webSocket;

    public Jetty9WebSocketHandler(HttpServletRequest request, AtmosphereFramework framework, WebSocketProcessor webSocketProcessor) {
        this.framework = framework;
        this.request = cloneRequest(request);
        this.webSocketProcessor = webSocketProcessor;
    }

    private AtmosphereRequest cloneRequest(final HttpServletRequest request) {
        try {
            AtmosphereRequest r = AtmosphereRequest.wrap(request);
            return AtmosphereRequest.cloneRequest(r, false, false, false);
        } catch (Exception ex) {
            logger.error("", ex);
            throw new RuntimeException("Invalid WebSocket Request");
        }
    }

    @Override
    public void onWebSocketBinary(byte[] data, int offset, int length) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, data, offset, length);
    }

    @Override
    public void onWebSocketClose(int closeCode, String s) {
        logger.trace("onClose {}:{}", closeCode, s);
        try {
            webSocketProcessor.close(webSocket, closeCode);
        } finally {
            request.destroy();
        }
    }

    @Override
    public void onWebSocketConnect(Session session) {
        logger.trace("WebSocket.onOpen.");
        webSocket = new Jetty9WebSocket(session, framework.getAtmosphereConfig());
        try {
            webSocketProcessor.open(webSocket, request, AtmosphereResponse.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        } catch (Exception e) {
            logger.warn("Failed to connect to WebSocket", e);
        }
    }

    @Override
    public void onWebSocketError(Throwable e) {
        logger.error("", e);
        webSocketProcessor.notifyListener(webSocket,
                new WebSocketEventListener.WebSocketEvent(e, WebSocketEventListener.WebSocketEvent.TYPE.EXCEPTION, webSocket));
    }

    @Override
    public void onWebSocketText(String s) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, s);
    }
}
