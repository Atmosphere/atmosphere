package org.atmosphere.container.version;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import org.atmosphere.container.ResinWebSocketHandler;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;

import com.caucho.websocket.WebSocketContext;

public class ResinWebSocket extends WebSocket {
    private final ResinWebSocketHandler handler;

    private final WebSocketContext context;

    public ResinWebSocket(final ResinWebSocketHandler handler, final WebSocketContext context, final AtmosphereConfig config) {
        super(config);

        this.handler = handler;
        this.context = context;
    }

    @Override
    public String toString() {
        return context.toString();
    }

    @Override
    public boolean isOpen() {
        return handler.isOpen();
    }

    @Override
    public WebSocket write(final String s) throws IOException {
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");

        try (PrintWriter writer = context.startTextMessage()) {
            writer.print(s);
        }

        return this;
    }

    @Override
    public WebSocket write(final byte[] b, final int offset, final int length) throws IOException {
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");

        try (OutputStream ostr = context.startBinaryMessage()) {
            ostr.write(b, offset, length);
        }

        return this;
    }

    @Override
    public void close() {
        logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");

        context.close();
    }

    @Override
    public WebSocket flush(final AtmosphereResponse r) throws IOException {
        logger.trace("WebSocket.flush() for {}", r.uuid());

        context.flush();

        return this;
    }
}
