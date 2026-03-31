/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.KeepOpenStreamAware;
import org.atmosphere.util.ByteArrayAsyncWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * Abstract representation of a WebTransport bidirectional stream session.
 * Mirrors {@link org.atmosphere.websocket.WebSocket} for WebTransport over
 * HTTP/3, backed by a single QUIC bidirectional stream.
 *
 * <p>Unlike WebSocket, there is no application-level ping/pong — QUIC handles
 * keep-alive at the transport layer. Close codes use HTTP/3 error codes
 * (0-255), not WebSocket codes (1000-4999).</p>
 *
 * <p>Concrete implementations are provided by container modules (e.g., the
 * Spring Boot starter's Reactor Netty integration).</p>
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebTransportSession extends AtmosphereInterceptorWriter implements KeepOpenStreamAware {

    protected static final Logger logger = LoggerFactory.getLogger(WebTransportSession.class);

    public static final String WEBTRANSPORT_INITIATED = WebTransportSession.class.getName() + ".initiated";
    public static final String WEBTRANSPORT_SUSPEND = WebTransportSession.class.getName() + ".suspend";
    public static final String NOT_SUPPORTED = "WebTransport protocol not supported";
    public static final String CLEAN_CLOSE = "Clean_Close";

    private AtmosphereResource r;
    protected long lastWrite;
    protected boolean binaryWrite;
    private final AtomicBoolean firstWrite = new AtomicBoolean(false);
    private final AtmosphereConfig config;
    private WebTransportHandler webTransportHandler;
    protected String uuid = "NUll";
    private final ReentrantLock shiftAttributesLock = new ReentrantLock();
    private Map<String, Object> attributesAtSessionOpen;
    private Object attachment;

    protected WebTransportSession(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.WEBTRANSPORT_BINARY_WRITE);
        if (Boolean.parseBoolean(s)) {
            binaryWrite = true;
        }
        this.config = config;
    }

    public AtmosphereConfig config() {
        return config;
    }

    protected WebTransportSession webTransportHandler(WebTransportHandler handler) {
        this.webTransportHandler = handler;
        return this;
    }

    public WebTransportHandler webTransportHandler() {
        return webTransportHandler;
    }

    /**
     * Associate an {@link AtmosphereResource} with this session.
     *
     * @param r the resource
     * @return this
     */
    public WebTransportSession resource(AtmosphereResource r) {
        if (this.r != null && r != null) {
            ((AtmosphereResourceImpl) r).cloneState(this.r);
        }
        this.r = r;
        if (r != null) uuid = r.uuid();
        return this;
    }

    public AtmosphereResource resource() {
        return r;
    }

    /**
     * Snapshot the request attributes that were set when the session opened.
     */
    public void shiftAttributes() {
        shiftAttributesLock.lock();
        try {
            attributesAtSessionOpen = new ConcurrentHashMap<>();
            attributesAtSessionOpen.putAll(
                    ((AtmosphereResourceImpl) r).getRequest(false).localAttributes().unmodifiableMap());
        } finally {
            shiftAttributesLock.unlock();
        }
    }

    public Map<String, Object> attributes() {
        return attributesAtSessionOpen;
    }

    public String uuid() {
        return uuid;
    }

    public long lastWriteTimeStampInMilliseconds() {
        return lastWrite == -1 ? System.currentTimeMillis() : lastWrite;
    }

    /**
     * Broadcast an object to all sessions sharing this resource's
     * {@link org.atmosphere.cpr.Broadcaster}.
     */
    public WebTransportSession broadcast(Object o) {
        if (r != null) {
            r.getBroadcaster().broadcast(o);
        } else {
            logger.debug("No AtmosphereResource associated with this WebTransportSession.");
        }
        return this;
    }

    // ── AtmosphereInterceptorWriter overrides ────────────────────────────

    @Override
    public WebTransportSession write(AtmosphereResponse r, String data) throws IOException {
        firstWrite.set(true);
        if (data == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }
        if (!isOpen()) throw new IOException("Connection remotely closed for " + uuid);
        logger.trace("WebTransportSession.write() {}", data);

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
            write(data);
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    @Override
    public WebTransportSession write(AtmosphereResponse r, byte[] data) throws IOException {
        if (data == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }
        return write(r, data, 0, data.length);
    }

    @Override
    public WebTransportSession write(AtmosphereResponse r, byte[] b, int offset, int length) throws IOException {
        firstWrite.set(true);
        if (b == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }
        if (!isOpen()) throw new IOException("Connection remotely closed for " + uuid);

        if (logger.isTraceEnabled()) {
            logger.trace("WebTransportSession.write() {}", new String(b, offset, length, StandardCharsets.UTF_8));
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
            String data;
            String charset = r.getCharacterEncoding() == null ? "UTF-8" : r.getCharacterEncoding();
            if (transform) {
                data = new String(transform(r, b, offset, length), charset);
            } else {
                data = new String(b, offset, length, charset);
            }
            write(data);
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    @Override
    public WebTransportSession writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        super.writeError(r, errorCode, message);
        if (!firstWrite.get()) {
            logger.debug("The WebTransport session opened but the dispatched URI failed with status {} : {} " +
                    "The session is still open and the client can continue sending messages.", errorCode + " " + message, uuid());
        } else {
            logger.warn("Unable to write {} {}", errorCode, message);
        }
        return this;
    }

    @Override
    public WebTransportSession redirect(AtmosphereResponse r, String location) throws IOException {
        logger.error("WebTransport redirect not supported");
        return this;
    }

    @Override
    public void close(AtmosphereResponse r) throws IOException {
        logger.trace("WebTransportSession.close() for {}", uuid);
        try {
            if (r.request() != null && r.request().getAttribute(CLEAN_CLOSE) == null) {
                close();
            }
        } catch (Exception ex) {
            logger.trace("", ex);
        }
    }

    @Override
    public WebTransportSession flush(AtmosphereResponse r) throws IOException {
        return this;
    }

    public static void notSupported(org.atmosphere.cpr.AtmosphereRequest request,
                                     AtmosphereResponse response) throws IOException {
        response.addHeader(X_ATMOSPHERE_ERROR, NOT_SUPPORTED);
        response.sendError(501, NOT_SUPPORTED);
        logger.trace("{} for request {}", NOT_SUPPORTED, request);
    }

    public WebTransportSession attachment(Object attachment) {
        this.attachment = attachment;
        return this;
    }

    public Object attachment() {
        return attachment;
    }

    // ── Transform helper ─────────────────────────────────────────────────

    protected byte[] transform(AtmosphereResponse response, byte[] b, int offset, int length) throws IOException {
        AsyncIOWriter a = response.getAsyncIOWriter();
        var buffer = new ByteArrayAsyncWriter();
        try {
            response.asyncIOWriter(buffer);
            invokeInterceptor(response, b, offset, length);
            return buffer.stream().toByteArray();
        } finally {
            buffer.close(null);
            response.asyncIOWriter(a);
        }
    }

    // ── Abstract methods — implemented by container-specific subclasses ──

    /**
     * Whether the underlying QUIC stream is still open.
     */
    public abstract boolean isOpen();

    /**
     * Write a text message to the bidirectional stream.
     */
    public abstract WebTransportSession write(String s) throws IOException;

    /**
     * Write binary data to the bidirectional stream.
     */
    public abstract WebTransportSession write(byte[] b, int offset, int length) throws IOException;

    /**
     * Write binary data to the bidirectional stream.
     */
    public WebTransportSession write(byte[] b) throws IOException {
        return write(b, 0, b.length);
    }

    /**
     * Close the session.
     */
    public abstract void close();

    /**
     * Close with an HTTP/3 error code and reason.
     *
     * @param errorCode HTTP/3 error code (0-255)
     * @param reason    human-readable reason
     */
    public void close(int errorCode, String reason) {
        close();
    }
}
