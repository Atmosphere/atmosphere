/*
* Copyright 2015 Async-IO.org
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
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.KeepOpenStreamAware;
import org.atmosphere.util.ByteArrayAsyncWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * Represent a portable WebSocket implementation which can be used to write message.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocket extends AtmosphereInterceptorWriter implements KeepOpenStreamAware {

    protected static final Logger logger = LoggerFactory.getLogger(WebSocket.class);
    public final static String WEBSOCKET_INITIATED = WebSocket.class.getName() + ".initiated";
    public final static String WEBSOCKET_SUSPEND = WebSocket.class.getName() + ".suspend";
    public final static String WEBSOCKET_RESUME = WebSocket.class.getName() + ".resume";
    public final static String WEBSOCKET_ACCEPT_DONE = WebSocket.class.getName() + ".acceptDone";
    public final static String NOT_SUPPORTED = "Websocket protocol not supported";
    public final static String CLEAN_CLOSE = "Clean_Close";

    private AtmosphereResource r;
    protected long lastWrite;
    protected boolean binaryWrite;
    private final AtomicBoolean firstWrite = new AtomicBoolean(false);
    private final AtmosphereConfig config;
    private WebSocketHandler webSocketHandler;
    protected ByteBuffer bb = ByteBuffer.allocate(8192);
    protected CharBuffer cb = CharBuffer.allocate(8192);
    protected String uuid = "NUll";
    private Map<String, Object> attributesAtWebSocketOpen;
    private Object attachment;

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

    /**
     * Switch to binary write, or go back to text write. Default is false.
     *
     * @param binaryWrite true to switch to binary write.
     * @return
     */
    public WebSocket binaryWrite(boolean binaryWrite) {
        this.binaryWrite = binaryWrite;
        return this;
    }

    public WebSocketHandler webSocketHandler() {
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
        if (r != null) uuid = r.uuid();
        return this;
    }

    /**
     * Copy {@link AtmosphereRequestImpl#localAttributes()} that where set when the websocket was opened.
     *
     * @return this.
     */
    public WebSocket shiftAttributes() {
        attributesAtWebSocketOpen = AtmosphereResourceImpl.class.cast(r).getRequest(false).localAttributes().unmodifiableMap();
        return this;
    }

    /**
     * Return the attribute that was set during the websocket's open operation.
     *
     * @return
     */
    public Map<String, Object> attributes() {
        return attributesAtWebSocketOpen;
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
        return transform(r.getResponse(), b, offset, length);
    }

    protected byte[] transform(AtmosphereResponse response, byte[] b, int offset, int length) throws IOException {
        AsyncIOWriter a = response.getAsyncIOWriter();
        // NOTE #1961 for now, create a new buffer par transform call and release it after the transform call.
        //      Alternatively, we may cache the buffer in thread-local and use it while this thread invokes
        //      multiple writes and release it when this thread invokes the close method.
        ByteArrayAsyncWriter buffer = new ByteArrayAsyncWriter();
        try {
            response.asyncIOWriter(buffer);
            invokeInterceptor(response, b, offset, length);
            return buffer.stream().toByteArray();
        } finally {
            buffer.close(null);
            response.asyncIOWriter(a);
        }
    }

    @Override
    public WebSocket write(AtmosphereResponse r, String data) throws IOException {
        firstWrite.set(true);
        if (data == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }

        if (!isOpen()) throw new IOException("Connection remotely closed for " + uuid);
        logger.trace("WebSocket.write() {}", data);

        boolean transform = !filters.isEmpty() && r.getStatus() < 400;
        if (binaryWrite) {
            byte[] b = data.getBytes(resource().getResponse().getCharacterEncoding());
            if (transform) {
                b = transform(r, b, 0, b.length);
            }

            if (b != null) {
                write(b, 0, b.length);
            }
        } else {
            if (transform) {
                byte[] b = data.getBytes(resource().getResponse().getCharacterEncoding());
                data = new String(transform(r, b, 0, b.length), r.getCharacterEncoding());
            }

            if (data != null) {
                write(data);
            }
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    @Override
    public WebSocket write(AtmosphereResponse r, byte[] data) throws IOException {
        if (data == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }
        return write(r, data, 0, data.length);
    }

    @Override
    public WebSocket write(AtmosphereResponse r, byte[] b, int offset, int length) throws IOException {
        firstWrite.set(true);
        if (b == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }
        if (!isOpen()) throw new IOException("Connection remotely closed for " + uuid);

        if (logger.isTraceEnabled()) {
            logger.trace("WebSocket.write() {}", new String(b, offset, length, "UTF-8"));
        }

        boolean transform = !filters.isEmpty() && r.getStatus() < 400;
        if (binaryWrite || resource().forceBinaryWrite()) {
            if (transform) {
                b = transform(r, b, offset, length);
            }

            if (b != null) {
                write(b, 0, b.length);
            }
        } else {
            String data = null;
            String charset = r.getCharacterEncoding() == null ? "UTF-8" : r.getCharacterEncoding();
            if (transform) {
                data = new String(transform(r, b, offset, length), charset);
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

    @Override
    public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        super.writeError(r, errorCode, message);
        if (!firstWrite.get()) {
            logger.debug("The WebSocket handshake succeeded but the dispatched URI failed with status {} : {} " +
                    "The WebSocket connection is still open and client can continue sending messages.", errorCode + " " + message, uuid());
        } else {
            logger.warn("Unable to write {} {}", errorCode, message);
        }

        return this;
    }

    @Override
    public WebSocket redirect(AtmosphereResponse r, String location) throws IOException {
        logger.error("WebSocket Redirect not supported");
        return this;
    }


    @Override
    public void close(AtmosphereResponse r) throws IOException {
        logger.trace("WebSocket.close() for {}", uuid);

        try {
            // Never trust underlying server.
            // https://github.com/Atmosphere/atmosphere/issues/1633
            if (r.request() != null && r.request().getAttribute(CLEAN_CLOSE) == null) {
                close();
            }
        } catch (Exception ex) {
            logger.trace("", ex);
        }

        try {
            bb.clear();
            cb.clear();
            // NOTE #1961 if the buffer is cached at thread-local, it needs to be released here.
        } catch (Exception ex) {
            logger.trace("", ex);
        }
    }

    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        return this;
    }

    /**
     * Is the underlying WebSocket open.
     *
     * @return true is opened
     */
    abstract public boolean isOpen();

    /**
     * Use the underlying container's websocket to write the String.
     *
     * @param s a websocket String message
     * @return this
     * @throws IOException
     */
    abstract public WebSocket write(String s) throws IOException;

    /**
     * Use the underlying container's websocket to write the byte.
     *
     * @param b      a websocket byte message
     * @param offset start
     * @param length end
     * @return this
     * @throws IOException
     */
    abstract public WebSocket write(byte[] b, int offset, int length) throws IOException;

    /**
     * Use the underlying container's websocket to write the byte.
     *
     * @param b      a websocket byte message
     * @return this
     * @throws IOException
     */
    public WebSocket write(byte[] b) throws IOException {
        return write(b, 0, b.length);
    }

    /**
     * Close the underlying WebSocket
     */
    abstract public void close();

    public String uuid() {
        return uuid;
    }

    public static void notSupported(AtmosphereRequest request, AtmosphereResponse response) throws IOException {
        response.addHeader(X_ATMOSPHERE_ERROR, WebSocket.NOT_SUPPORTED);
        response.sendError(501, WebSocket.NOT_SUPPORTED);
        logger.trace("{} for request {}", WebSocket.NOT_SUPPORTED, request);
    }

    /**
     * Send a WebSocket Ping
     *
     * @param payload the bytes to send
     * @return this
     */
    public WebSocket sendPing(byte[] payload) {
        throw new UnsupportedOperationException();
    }

    /**
     * Send a WebSocket Pong
     *
     * @param payload the bytes to send
     * @return this
     */
    public WebSocket sendPong(byte[] payload) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attach an object. Be careful when attaching an object as it can cause memory leak
     *
     * @oaram object
     */
    public WebSocket attachment(Object attachment) {
        this.attachment = attachment;
        return this;
    }

    /**
     * Return the attachment
     */
    public Object attachment() {
        return attachment;
    }
}
