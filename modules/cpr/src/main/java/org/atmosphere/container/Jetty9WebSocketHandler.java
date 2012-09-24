package org.atmosphere.container;

import org.atmosphere.container.version.Jetty9WebSocket;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.eclipse.jetty.websocket.core.api.UpgradeRequest;
import org.eclipse.jetty.websocket.core.api.WebSocketConnection;
import org.eclipse.jetty.websocket.core.api.WebSocketException;
import org.eclipse.jetty.websocket.core.api.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequestWrapper;

public class Jetty9WebSocketHandler implements WebSocketListener {

    private static final Logger logger = LoggerFactory.getLogger(Jetty9WebSocketHandler.class);

    private WebSocketProcessor webSocketProcessor;
    private final AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private final WebSocketProtocol webSocketProtocol;

    public Jetty9WebSocketHandler(UpgradeRequest request, AtmosphereFramework framework, WebSocketProtocol webSocketProtocol) {
        this.framework = framework;
        this.request = cloneRequest(request);
        this.webSocketProtocol = webSocketProtocol;
    }

    private AtmosphereRequest cloneRequest(final UpgradeRequest request) {
        try {
            AtmosphereRequest r = (AtmosphereRequest) HttpServletRequestWrapper.class.cast(request).getRequest();
            return AtmosphereRequest.cloneRequest(r, false, framework.getAtmosphereConfig().isSupportSession(), false);
        } catch (Exception ex) {
            throw new RuntimeException("Invalid WebSocket Request");
        }
    }

    @Override
    public void onWebSocketBinary(byte[] data, int offset, int length) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(data, offset, length);
    }

    @Override
    public void onWebSocketClose(int closeCode, String s) {
        request.destroy();
        if (webSocketProcessor == null) return;

        webSocketProcessor.close(closeCode);
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection webSocketConnection) {
        logger.trace("WebSocket.onOpen.");
        try {
            webSocketProcessor = WebSocketProcessorFactory.getDefault()
                    .newWebSocketProcessor(new Jetty9WebSocket(webSocketConnection, framework.getAtmosphereConfig()));
            webSocketProcessor.open(request);
        } catch (Exception e) {
            logger.warn("Failed to connect to WebSocket", e);
        }
    }

    @Override
    public void onWebSocketException(WebSocketException e) {
        // onError
    }

    @Override
    public void onWebSocketText(String s) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(s);
    }
}
