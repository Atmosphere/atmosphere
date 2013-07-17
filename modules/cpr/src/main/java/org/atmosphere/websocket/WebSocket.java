/*
* Copyright 2013 Jeanfrancois Arcand
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/
package org.atmosphere.websocket;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.ByteArrayAsyncWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * Represent a portable WebSocket implementation which can be used to write message.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocket extends AtmosphereInterceptorWriter {

    protected static final Logger logger = LoggerFactory.getLogger(WebSocket.class);
    public final static String WEBSOCKET_INITIATED = WebSocket.class.getName() + ".initiated";
    public final static String WEBSOCKET_SUSPEND = WebSocket.class.getName() + ".suspend";
    public final static String WEBSOCKET_RESUME = WebSocket.class.getName() + ".resume";
    public final static String WEBSOCKET_ACCEPT_DONE = WebSocket.class.getName() + ".acceptDone";
    public final static String NOT_SUPPORTED = "Websocket protocol not supported";

    private AtmosphereResource r;
    protected long lastWrite = 0;
    protected final boolean binaryWrite;
    private final ByteArrayAsyncWriter buffer = new ByteArrayAsyncWriter();
    private final AtomicBoolean firstWrite = new AtomicBoolean(false);
    private final AtmosphereConfig config;
    private WebSocketHandler webSocketHandler;

    public WebSocket(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_BINARY_WRITE);
        if (s != null && Boolean.parseBoolean(s)) {
            binaryWrite = true;
        } else {
            binaryWrite = false;
        }
        this.config = config;
    }

    public AtmosphereConfig config() {
        return config;
    }

    protected WebSocket webSocketHandler(WebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
        return this;
    }


    protected WebSocketHandler webSocketHandler() {
        return webSocketHandler;
    }

    /**
     * Associate an {@link AtmosphereResource} to this WebSocket
     *
     * @param r an {@link AtmosphereResource} to this WebSocket
     * @return this
     */
    public WebSocket resource(AtmosphereResource r) {

        // Make sure we carry what was set at the onOpen stage.
        if (this.r != null && r != null) {
            // TODO: This is all over the place and quite ugly (the cast). Need to fix this in 1.1
            AtmosphereResourceImpl.class.cast(r).cloneState(this.r);
        }
        this.r = r;
        return this;
    }

    /**
     * Return the an {@link AtmosphereResource} used by this WebSocket, or null if the WebSocket has been closed
     * before the WebSocket message has been processed.
     *
     * @return {@link AtmosphereResource}
     */
    public AtmosphereResource resource() {
        return r;
    }

    /**
     * The last time, in milliseconds, a write operation occurred.
     *
     * @return this
     */
    public long lastWriteTimeStampInMilliseconds() {
        return lastWrite == -1 ? System.currentTimeMillis() : lastWrite;
    }

    protected byte[] transform(byte[] b, int offset, int length) throws IOException {
        AtmosphereResponse response = r.getResponse();
        AsyncIOWriter a = response.getAsyncIOWriter();
        try {
            response.asyncIOWriter(buffer);
            invokeInterceptor(response, b, offset, length);
            return buffer.stream().toByteArray();
        } finally {
            buffer.close(null);
            response.asyncIOWriter(a);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, String data) throws IOException {
        firstWrite.set(true);
        if (!isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");

        boolean transform = filters.size() > 0 && r.getStatus() < 400;
        if (binaryWrite) {
            byte[] b = data.getBytes(resource().getResponse().getCharacterEncoding());
            if (transform) {
                b = transform(b, 0, b.length);
            }

            if (b != null) {
                write(b, 0, b.length);
            }
        } else {
            if (transform) {
                byte[] b = data.getBytes(resource().getResponse().getCharacterEncoding());
                data = new String(transform(b, 0, b.length), r.getCharacterEncoding());
            }

            if (data != null) {
                write(data);
            }
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, byte[] data) throws IOException {
        return write(r, data, 0, data.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, byte[] b, int offset, int length) throws IOException {
        firstWrite.set(true);
        if (!isOpen()) throw new IOException("Connection remotely closed");

        logger.trace("WebSocket.write()");
        boolean transform = filters.size() > 0 && r.getStatus() < 400;
        if (binaryWrite) {
            if (transform) {
                b = transform(b, offset, length);
            }

            if (b != null) {
                write(b, 0, length);
            }
        } else {
            String data = null;
            String charset = r.getCharacterEncoding() == null ? "UTF-8" : r.getCharacterEncoding();
            if (transform) {
                data = new String(transform(b, offset, length), charset);
            } else {
                data = new String(b, offset, length, charset);
            }

            if (data != null) {
                write(data);
            }
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    /**
     * Broadcast, using the {@link org.atmosphere.cpr.AtmosphereResource#getBroadcaster()} the object to all
     * {@link WebSocket} associated with the {@link org.atmosphere.cpr.Broadcaster}. This method does the same as
     * websocket.resource().getBroadcaster().broadcast(o).
     *
     * @param o An object to broadcast to all WebSockets.
     */
    public WebSocket broadcast(Object o) {
        if (r != null) {
            r.getBroadcaster().broadcast(o);
        } else {
            logger.debug("No AtmosphereResource Associated with this WebSocket.");
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        super.writeError(r, errorCode, message);
        if (!firstWrite.get()) {
            logger.debug("The WebSocket handshake succeeded but the dispatched URI failed with status {} : {} " +
                    "The WebSocket connection is still open and client can continue sending messages.", errorCode + message, retrieveUUID());
        } else {
            logger.debug("{} {}", errorCode, message);
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket redirect(AtmosphereResponse r, String location) throws IOException {
        logger.error("WebSocket Redirect not supported");
        return this;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void close(AtmosphereResponse r) throws IOException {
        logger.trace("WebSocket.close()");
        close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        return this;
    }

    /**
     * Is the underlying WebSocket open.
     *
     * @return
     */
    abstract public boolean isOpen();

    abstract public WebSocket write(String s) throws IOException;

    abstract public WebSocket write(byte[] b, int offset, int length) throws IOException;

    abstract public void close();

    protected String retrieveUUID() {
        return r == null ? "null" : r.uuid();
    }

    public static void notSupported(AtmosphereRequest request, AtmosphereResponse response) throws IOException {
        response.addHeader(X_ATMOSPHERE_ERROR, WebSocket.NOT_SUPPORTED);
        response.sendError(501, WebSocket.NOT_SUPPORTED);
        logger.trace("{} for request {}", WebSocket.NOT_SUPPORTED, request);
    }
}
