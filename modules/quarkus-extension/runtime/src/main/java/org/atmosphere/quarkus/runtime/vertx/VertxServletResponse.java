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
package org.atmosphere.quarkus.runtime.vertx;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The native response under an Atmosphere response in the Vert.x container
 * mode — the role Undertow's {@code HttpServletResponse} plays in servlet mode.
 * Atmosphere wraps it exactly as it wraps a servlet response
 * ({@code AtmosphereResponseImpl.wrap}), so its interceptor writer (SSE,
 * padding, message framing) sits on top unchanged and this class is only the
 * terminal sink.
 *
 * <p>Status and headers are held until the first byte is written (or the
 * response ends), then committed to the Vert.x {@link HttpServerResponse}. Writes
 * honour Vert.x backpressure: a writer on a virtual thread waits for the drain
 * signal, bounded by {@code drainTimeoutMs}; a consumer that never drains has its
 * connection reset instead of buffering without limit (Correctness Invariant #3).</p>
 */
final class VertxServletResponse implements HttpServletResponse {

    private static final Logger logger = LoggerFactory.getLogger(VertxServletResponse.class);

    private final HttpServerResponse response;
    private final long drainTimeoutMs;
    private final AtomicBoolean committed = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final ServletOutputStream out = new VertxOutputStream();
    private int status = 200;
    private String contentType;
    private String characterEncoding = StandardCharsets.UTF_8.name();
    private Locale locale = Locale.getDefault();
    private PrintWriter writer;

    VertxServletResponse(HttpServerResponse response, long drainTimeoutMs) {
        this.response = response;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    /** True once this side ended or reset the exchange (not a client disconnect). */
    boolean completedByServer() {
        return ended.get();
    }

    private boolean gone() {
        return ended.get() || response.ended() || response.closed();
    }

    /** End the exchange, committing status and headers if nothing was written yet. */
    void finish() {
        if (gone()) {
            return;
        }
        if (writer != null) {
            writer.flush();
        }
        commit();
        end(null);
    }

    /** Fail the exchange: a 500 if nothing was sent yet, otherwise a connection reset. */
    void fail() {
        if (gone()) {
            return;
        }
        if (committed.compareAndSet(false, true)) {
            response.setStatusCode(500);
            end(null);
        } else {
            response.reset();
            ended.set(true);
        }
    }

    private void end(String body) {
        if (ended.compareAndSet(false, true) && !response.ended() && !response.closed()) {
            if (body == null || body.isEmpty()) {
                response.end();
            } else {
                response.end(body);
            }
        }
    }

    private void commit() {
        if (!committed.compareAndSet(false, true)) {
            return;
        }
        response.setStatusCode(status);
        if (contentType != null && !response.headers().contains("Content-Type")) {
            response.putHeader("Content-Type", contentType);
        }
        if (!response.headers().contains("Content-Length")) {
            response.setChunked(true);
        }
    }

    private void write(byte[] b, int off, int len) throws IOException {
        if (gone()) {
            throw new IOException("Vert.x response already closed");
        }
        commit();
        response.write(Buffer.buffer().appendBytes(b, off, len));
        awaitDrain();
    }

    private void awaitDrain() throws IOException {
        if (!response.writeQueueFull() || Context.isOnEventLoopThread()) {
            // Never block an event loop; Vert.x keeps queueing for that caller.
            return;
        }
        var drained = new CountDownLatch(1);
        response.drainHandler(v -> drained.countDown());
        if (!response.writeQueueFull()) {
            return;
        }
        try {
            if (!drained.await(drainTimeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("HTTP client did not drain within {}ms; resetting the connection", drainTimeoutMs);
                response.reset();
                ended.set(true);
                throw new IOException("Slow consumer: write queue did not drain");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for drain", e);
        }
    }

    private final class VertxOutputStream extends ServletOutputStream {
        @Override
        public boolean isReady() {
            return !gone() && !response.writeQueueFull();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // Blocking I/O on a virtual thread; the async write-listener model is not used.
        }

        @Override
        public void write(int b) throws IOException {
            VertxServletResponse.this.write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            VertxServletResponse.this.write(b, off, len);
        }

        @Override
        public void flush() {
            commit();
        }

        @Override
        public void close() {
            finish();
        }
    }

    // ── ServletResponse ──────────────────────────────────────────────────

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return out;
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(out, Charset.forName(characterEncoding)), true);
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        if (charset != null && !committed.get()) {
            characterEncoding = charset;
        }
    }

    @Override
    public void setContentLength(int len) {
        setContentLengthLong(len);
    }

    @Override
    public void setContentLengthLong(long len) {
        if (!committed.get() && len >= 0) {
            response.putHeader("Content-Length", Long.toString(len));
        }
    }

    @Override
    public void setContentType(String type) {
        if (!committed.get()) {
            contentType = type;
        }
    }

    @Override
    public void setBufferSize(int size) {
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void flushBuffer() {
        if (writer != null) {
            writer.flush();
        }
        commit();
    }

    @Override
    public void resetBuffer() {
    }

    @Override
    public boolean isCommitted() {
        return committed.get();
    }

    @Override
    public void reset() {
        if (!committed.get()) {
            response.headers().clear();
            status = 200;
            contentType = null;
        }
    }

    @Override
    public void setLocale(Locale loc) {
        if (loc != null) {
            locale = loc;
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    // ── HttpServletResponse ──────────────────────────────────────────────

    @Override
    public void addCookie(Cookie cookie) {
        if (!committed.get()) {
            response.headers().add("Set-Cookie", org.atmosphere.util.CookieUtil.toString(cookie));
        }
    }

    @Override
    public boolean containsHeader(String name) {
        return response.headers().contains(name);
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        if (committed.get()) {
            throw new IllegalStateException("Response already committed");
        }
        status = sc;
        commit();
        end(msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, null);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        if (committed.get()) {
            throw new IllegalStateException("Response already committed");
        }
        status = 302;
        response.putHeader("Location", location);
        commit();
        end(null);
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, httpDate(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, httpDate(date));
    }

    @Override
    public void setHeader(String name, String value) {
        if (committed.get() || name == null) {
            return;
        }
        if ("Content-Type".equalsIgnoreCase(name)) {
            contentType = value;
            return;
        }
        if (value == null) {
            response.headers().remove(name);
        } else {
            response.putHeader(name, value);
        }
    }

    @Override
    public void addHeader(String name, String value) {
        if (committed.get() || name == null || value == null) {
            return;
        }
        if ("Content-Type".equalsIgnoreCase(name)) {
            contentType = value;
            return;
        }
        response.headers().add(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    @Override
    public void setStatus(int sc) {
        if (!committed.get()) {
            status = sc;
        }
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getHeader(String name) {
        if ("Content-Type".equalsIgnoreCase(name)) {
            return contentType;
        }
        return response.headers().get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return new ArrayList<>(response.headers().getAll(name));
    }

    @Override
    public Collection<String> getHeaderNames() {
        List<String> names = new ArrayList<>(response.headers().names());
        if (contentType != null && !names.contains("Content-Type")) {
            names.add("Content-Type");
        }
        return names;
    }

    private static String httpDate(long epochMillis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }
}
