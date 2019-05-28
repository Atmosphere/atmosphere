package jp.co.intra_mart.system.atmosphere.container;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicBoolean;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caucho.websocket.WebSocketContext;
import com.caucho.websocket.WebSocketListener;

import jp.co.intra_mart.system.atmosphere.container.version.ResinWebSocket;

public class ResinWebSocketHandler implements WebSocketListener {
    private static final Logger logger = LoggerFactory.getLogger(ResinWebSocketHandler.class);

    private final AtmosphereRequest request;

    private final AtmosphereFramework framework;

    private final WebSocketProcessor webSocketProcessor;

    private WebSocket webSocket;

    private AtomicBoolean isOpen = new AtomicBoolean();

    public ResinWebSocketHandler(final AtmosphereRequest request, final AtmosphereFramework framework, final WebSocketProcessor webSocketProcessor) {
        this.request = request;
        this.framework = framework;
        this.webSocketProcessor = webSocketProcessor;
    }

    @Override
    public void onClose(final WebSocketContext context) throws IOException {
        logger.debug("onClose");

        try {
            webSocketProcessor.close(webSocket, 1005);
            isOpen.set(false);
        } finally {
            request.destroy();
        }
    }

    @Override
    public void onDisconnect(final WebSocketContext context) throws IOException {
        logger.debug("onDisconnect");

        isOpen.set(false);
    }

    @Override
    public void onReadBinary(final WebSocketContext context, final InputStream is) throws IOException {
        logger.debug("onReadBinary");

        webSocketProcessor.invokeWebSocketProtocol(webSocket, is);
    }

    @Override
    public void onReadText(final WebSocketContext context, final Reader reader) throws IOException {
        logger.debug("onReadText");

        webSocketProcessor.invokeWebSocketProtocol(webSocket, reader);
    }

    @Override
    public void onStart(final WebSocketContext context) throws IOException {
        logger.debug("onStart");

        try {
            isOpen.set(true);

            webSocket = new ResinWebSocket(this, context, framework.getAtmosphereConfig());
            webSocketProcessor.open(webSocket, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        } catch (final Exception e) {
            logger.warn("Failed to connect to WebSocket", e);
        }
    }

    @Override
    public void onTimeout(final WebSocketContext context) throws IOException {
        logger.debug("onTimeout");

        isOpen.set(false);
    }

    public boolean isOpen() {
        return isOpen.get();
    }
}
