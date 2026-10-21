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
package org.atmosphere.cpr;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.util.HtmlEncoder;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;

/**
 * Encapsulates the async writing and I/O delegation logic for {@link AtmosphereResponseImpl}.
 * This class manages the {@link AsyncIOWriter}, stream/writer creation, buffering,
 * flush/close operations, and the delegation decision between native response and async writer.
 *
 * <p>This is an internal helper class and is not part of the public API.</p>
 */
final class ResponseWriter {

    private static final Logger logger = LoggerFactory.getLogger(ResponseWriter.class);
    private static final ThreadLocal<Object> NO_BUFFERING = new ThreadLocal<>();

    private AsyncIOWriter asyncIOWriter;
    private boolean delegateToNativeResponse;
    private boolean forceAsyncIOWriter;
    private final AtomicBoolean writeStatusAndHeader;
    private final AtomicBoolean usingStream = new AtomicBoolean(true);
    private final AtomicReference<Object> buffered = new AtomicReference<>(null);
    private boolean completed;

    ResponseWriter(AsyncIOWriter asyncIOWriter, boolean writeStatusAndHeader) {
        this.asyncIOWriter = asyncIOWriter;
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.writeStatusAndHeader = new AtomicBoolean(writeStatusAndHeader);
    }

    // -- AsyncIOWriter management --

    AsyncIOWriter getAsyncIOWriter() {
        return writer();
    }

    /**
     * A writer installed for one thread only ({@link #writeThrough}), over the
     * writer of an outer one of the same thread, if any.
     */
    private record ThreadWriter(ResponseWriter owner, AsyncIOWriter writer, ThreadWriter outer) {
    }

    /** The writers {@link #writeThrough} installed on the current thread, innermost first. */
    private static final ThreadLocal<ThreadWriter> THREAD_WRITERS = new ThreadLocal<>();

    /**
     * The writer this response writes through on the calling thread: the one
     * {@link #writeThrough} installed for it, else the response's own.
     */
    private AsyncIOWriter writer() {
        for (var w = THREAD_WRITERS.get(); w != null; w = w.outer()) {
            if (w.owner() == this) {
                return w.writer();
            }
        }
        return asyncIOWriter;
    }

    /**
     * Whether the response writes through its {@link AsyncIOWriter} rather than
     * the native response: as told ({@link #setForceAsyncIOWriter}, or a writer
     * installed with {@link #setAsyncIOWriter}), or on a thread a writer
     * {@link #writeThrough} installed is in effect on.
     */
    private boolean forced() {
        if (forceAsyncIOWriter) {
            return true;
        }
        for (var w = THREAD_WRITERS.get(); w != null; w = w.outer()) {
            if (w.owner() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Run {@code action} with this response writing through {@code writer} on
     * the calling thread only: another thread writing to the response meanwhile
     * writes through the response's own writer, not into {@code writer}. A
     * WebSocket or WebTransport write runs its interceptors that way, collecting
     * what they write in a buffer of its own; installed with
     * {@link #setAsyncIOWriter}, the buffer took the frames other threads wrote
     * to the connection meanwhile (a room's live frame, the frames a reattach
     * writes), and a frame written once the buffer was read was lost.
     */
    <E extends Exception> void writeThrough(AsyncIOWriter writer, AtmosphereResponse.WriteAction<E> action) throws E {
        var outer = THREAD_WRITERS.get();
        THREAD_WRITERS.set(new ThreadWriter(this, writer, outer));
        try {
            action.run();
        } finally {
            if (outer != null) {
                THREAD_WRITERS.set(outer);
            } else {
                THREAD_WRITERS.remove();
            }
        }
    }

    void setAsyncIOWriter(AsyncIOWriter asyncIOWriter) {
        this.asyncIOWriter = asyncIOWriter;
        this.forceAsyncIOWriter = true;
    }

    // -- Delegation flags --

    boolean isDelegateToNativeResponse() {
        return delegateToNativeResponse;
    }

    void setDelegateToNativeResponse(boolean delegateToNativeResponse) {
        this.delegateToNativeResponse = delegateToNativeResponse;
    }

    boolean isForceAsyncIOWriter() {
        return forceAsyncIOWriter;
    }

    void setForceAsyncIOWriter(boolean forceAsyncIOWriter) {
        this.forceAsyncIOWriter = forceAsyncIOWriter;
    }

    boolean getWriteStatusAndHeader() {
        return writeStatusAndHeader.get();
    }

    void setWriteStatusAndHeader(boolean value) {
        writeStatusAndHeader.set(value);
    }

    // -- Validation helpers --

    void validAsyncIOWriter(String uuid) throws IOException {
        if (writer() == null) {
            logger.trace("{} invalid state", uuid);
            throw new IOException("AtmosphereResource Cancelled: " + uuid);
        }
    }

    boolean validFlushOrClose(String uuid) {
        if (writer() == null) {
            logger.warn("AtmosphereResponse for {} has been closed", uuid);
            return false;
        }
        return true;
    }

    // -- Status and header writing --

    void writeStatusAndHeaders(AtmosphereResponseImpl response) throws IOException {
        if (writeStatusAndHeader.getAndSet(false) && !forced()) {
            writer().write(response, constructStatusAndHeaders(response));
        }
    }

    private String constructStatusAndHeaders(AtmosphereResponseImpl response) {
        Map<String, String> headers = response.headers();
        var b = new StringBuilder("HTTP/1.1")
                .append(" ")
                .append(response.getStatus())
                .append(" ")
                .append(response.getStatusMessage())
                .append("\r\n");

        b.append("Content-Type").append(":")
                .append(headers.get("Content-Type") == null ? response.getContentType() : headers.get("Content-Type"))
                .append("\r\n");
        long contentLength = response.contentLengthValue();
        if (contentLength != -1) {
            b.append("Content-Length").append(":").append(contentLength).append("\r\n");
        }

        for (String s : headers.keySet()) {
            if (!s.equalsIgnoreCase("Content-Type")) {
                b.append(s).append(":").append(headers.get(s)).append("\r\n");
            }
        }
        b.deleteCharAt(b.length() - 2);
        b.append("\r\n\r\n");
        return b.toString();
    }

    // -- Stream/Writer creation --

    ServletOutputStream createOutputStream(AtmosphereResponseImpl response, HttpServletResponse nativeResponse) throws IOException {
        if (forced() || !delegateToNativeResponse) {
            return new Stream(response, isBuffering(response));
        } else {
            return nativeResponse.getOutputStream() != null ? nativeResponse.getOutputStream() : new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return false;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                }

                @Override
                public void write(int b) {
                }
            };
        }
    }

    PrintWriter createWriter(AtmosphereResponseImpl response, HttpServletResponse nativeResponse) throws IOException {
        if (forced() || !delegateToNativeResponse) {
            return new Writer(response, new Stream(response, isBuffering(response)));
        } else {
            return nativeResponse.getWriter() != null ? nativeResponse.getWriter() : new PrintWriter(new StringWriter());
        }
    }

    // -- Error/Redirect delegation --

    void sendError(AtmosphereResponseImpl response, HttpServletResponse nativeResponse, int sc, String msg) throws IOException {
        if (forced() || !delegateToNativeResponse) {
            response.setStatus(sc);

            // Prevent StackOverflow — temporarily clear field; restored after write
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false; // read by asyncIOWriter.writeError()
            writer().writeError(response, sc, msg);
            forceAsyncIOWriter = b;
        } else {
            if (!nativeResponse.isCommitted()) {
                nativeResponse.sendError(sc, msg);
            } else {
                logger.warn("Committed error code {} {}", sc, msg);
            }
        }
    }

    void sendErrorNoMessage(AtmosphereResponseImpl response, HttpServletResponse nativeResponse, int sc) throws IOException {
        if (forced() || !delegateToNativeResponse) {
            response.setStatus(sc);
            // Prevent StackOverflow — temporarily clear field; restored after write
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false; // read by asyncIOWriter.writeError()
            writer().writeError(response, sc, "");
            forceAsyncIOWriter = b;
        } else {
            if (!nativeResponse.isCommitted()) {
                nativeResponse.sendError(sc);
            } else {
                logger.warn("Committed error code {}", sc);
            }
        }
    }

    void sendRedirect(AtmosphereResponseImpl response, HttpServletResponse nativeResponse, String location) throws IOException {
        if (forced() || !delegateToNativeResponse) {
            // Prevent StackOverflow — temporarily clear field; restored after write
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false; // read by asyncIOWriter.redirect()
            writer().redirect(response, location);
            forceAsyncIOWriter = b;
        } else {
            nativeResponse.sendRedirect(location);
        }
    }

    // -- Close operations --

    void close(AtmosphereResponseImpl response) throws IOException {
        var w = writer();
        if (w != null) {
            w.close(response);
        }
    }

    void closeStreamOrWriter(AtmosphereResponseImpl response) {
        if (response.resource() != null) {
            try {
                if (isUsingStream(response)) {
                    response.getOutputStream().close();
                } else {
                    response.getWriter().close();
                }
            } catch (Exception e) {
                //https://github.com/Atmosphere/atmosphere/issues/1643
                logger.trace("Unexpected exception", e);
            }
        }
    }

    // -- Write operations --

    void writeString(AtmosphereResponseImpl response, HttpServletResponse nativeResponse,
                     String data, boolean writeUsingOriginalResponse) {
        if (nativeResponse instanceof java.lang.reflect.Proxy) {
            writeUsingOriginalResponse = false;
        }

        // Sanitized above via HtmlEncoder.encode() in sanitizeForOutput() —
        // CodeQL doesn't recognize the custom sanitizer (security/code-scanning#43).
        String sanitized = sanitizeForOutput(data);
        try {
            if (isUsingStream(response)) {
                try {
                    OutputStream o = writeUsingOriginalResponse ? nativeResponse.getOutputStream() : response.getOutputStream();
                    o.write(sanitized.getBytes(response.getCharacterEncoding())); // lgtm[java/xss]
                } catch (IllegalStateException ex) {
                    logger.trace("", ex);
                }
            } else {
                PrintWriter w = writeUsingOriginalResponse ? nativeResponse.getWriter() : response.getWriter();
                w.write(sanitized); // lgtm[java/xss]
            }
        } catch (Exception ex) {
            handleException(response, ex);
            throw new RuntimeException("I/O write failure", ex);
        }
    }

    void writeBytes(AtmosphereResponseImpl response, HttpServletResponse nativeResponse,
                    byte[] data, boolean writeUsingOriginalResponse) {
        if (data == null) {
            logger.error("Cannot write null value for {}", response.resource());
            return;
        }

        if (nativeResponse instanceof java.lang.reflect.Proxy) {
            writeUsingOriginalResponse = false;
        }

        try {
            if (isUsingStream(response)) {
                try {
                    OutputStream o = writeUsingOriginalResponse ? nativeResponse.getOutputStream() : response.getOutputStream();
                    o.write(data);
                } catch (IllegalStateException ex) {
                    logger.trace("", ex);
                }
            } else {
                PrintWriter w = writeUsingOriginalResponse ? nativeResponse.getWriter() : response.getWriter();
                w.write(sanitizeForOutput(new String(data, response.getCharacterEncoding()))); // lgtm[java/xss]
            }
        } catch (Exception ex) {
            handleException(response, ex);
            throw new RuntimeException("I/O write failure", ex);
        }
    }

    void writeBytes(AtmosphereResponseImpl response, HttpServletResponse nativeResponse,
                    byte[] data, int offset, int length, boolean writeUsingOriginalResponse) {
        if (data == null) {
            logger.error("Cannot write null value for {}", response.resource());
            return;
        }

        if (nativeResponse instanceof java.lang.reflect.Proxy) {
            writeUsingOriginalResponse = false;
        }

        try {
            if (isUsingStream(response)) {
                try {
                    OutputStream o = writeUsingOriginalResponse ? nativeResponse.getOutputStream() : response.getOutputStream();
                    o.write(data, offset, length);
                } catch (IllegalStateException ex) {
                    logger.trace("", ex);
                }
            } else {
                PrintWriter w = writeUsingOriginalResponse ? nativeResponse.getWriter() : response.getWriter();
                w.write(sanitizeForOutput(new String(data, offset, length, response.getCharacterEncoding()))); // lgtm[java/xss]
            }
        } catch (Exception ex) {
            handleException(response, ex);
            throw new RuntimeException("I/O write failure", ex);
        }
    }

    // -- Stream usage decision --

    boolean isUsingStream(AtmosphereResponseImpl response) {
        AtmosphereRequest atmosphereRequest = response.request();
        if (atmosphereRequest != null) {
            Object s = atmosphereRequest.getAttribute(PROPERTY_USE_STREAM);
            if (s != null) {
                usingStream.set((Boolean) s);
            }
        }

        // Property always take first.
        if (response.resource() != null) {
            boolean force = response.resource().forceBinaryWrite();
            if (!usingStream.get() && force) {
                usingStream.set(true);
            }
        }
        return usingStream.get();
    }

    // -- Sanitization --

    /**
     * Sanitize string output to prevent XSS. Encoding is applied when the content type
     * is HTML or when no content type has been set (secure by default — prevents content
     * sniffing attacks). For explicitly non-HTML content types (JSON, plain text, etc.),
     * data passes through unchanged. WebSocket frames are consumed by JavaScript, not
     * rendered as HTML, so they are never sanitized regardless of the content type.
     */
    String sanitizeForOutput(String data) {
        if (data == null) {
            return null;
        }
        // WebSocket frames are not rendered as HTML by the browser
        if (writer() instanceof WebSocket) {
            return data;
        }
        String ct = getContentTypeForSanitization();
        if (ct == null || ct.toLowerCase(java.util.Locale.ROOT).contains("html")) {
            return HtmlEncoder.encode(data);
        }
        return data;
    }

    // Package-private for use by the owning response
    private String contentTypeForSanitization;

    void setContentTypeForSanitization(String contentType) {
        this.contentTypeForSanitization = contentType;
    }

    private String getContentTypeForSanitization() {
        return contentTypeForSanitization;
    }

    // -- Exception handling --

    void handleException(AtmosphereResponseImpl response, Exception ex) {
        AtmosphereResource r = response.resource();
        if (r != null) {
            r.notifyListeners(
                    new AtmosphereResourceEventImpl((AtmosphereResourceImpl) r, true, false));
            // Don't take any risk and remove it. By identity: the write failed on this
            // resource, not on the client's next poll registered under the same uuid.
            ((AtmosphereResourceImpl) r).unregister();
        }
        logger.trace("{} unexpected I/O exception {}", response.uuid(), ex);
    }

    // -- Buffering --

    void writeWithBuffering(AtmosphereResponseImpl response, Object data) throws IOException {
        if (NO_BUFFERING.get() != null) {
            boolean b = forceAsyncIOWriter;
            try {
                if (data instanceof String s) {
                    writer().write(response, s);
                } else if (data instanceof byte[] bytes) {
                    writer().write(response, bytes);
                }
            } catch (IOException e) {
                handleException(response, e);
                throw e;
            } finally {
                forceAsyncIOWriter = b;
            }
        } else {
            try {
                NO_BUFFERING.set(Boolean.TRUE);
                Object previous = buffered.getAndSet(data);
                if (previous != null) {
                    boolean b = forceAsyncIOWriter;
                    try {
                        if (previous instanceof String s) {
                            writer().write(response, s);
                        } else if (previous instanceof byte[] bytes) {
                            writer().write(response, bytes);
                        }
                    } catch (IOException e) {
                        handleException(response, e);
                        throw e;
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }
            } finally {
                NO_BUFFERING.remove();
            }
        }
    }

    boolean isBuffering(AtmosphereResponseImpl response) {
        AtmosphereRequest atmosphereRequest = response.request();
        return atmosphereRequest != null
                && Boolean.TRUE == atmosphereRequest.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_AWARE);
    }

    // -- Completion --

    void onComplete(AtmosphereResponseImpl response) {
        if (!completed) {
            completed = true;
            try {
                writeWithBuffering(response, null);
            } catch (IOException e) {
                // ignore as the exception is already handled
            } finally {
                //reset the completion status for the subsequent push writes
                if (isCompletionReset(response)) {
                    completed = false;
                }
            }
        }
    }

    boolean completed() {
        return completed;
    }

    private boolean isCompletionReset(AtmosphereResponseImpl response) {
        AtmosphereRequest atmosphereRequest = response.request();
        return atmosphereRequest != null
                && Boolean.TRUE == atmosphereRequest.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_RESET);
    }

    // -- Destroy support --

    void destroy() {
        asyncIOWriter = null;
    }

    // -- Inner class: Stream --

    private final class Stream extends ServletOutputStream {
        private final boolean buffering;
        private final AtmosphereResponseImpl response;

        Stream(AtmosphereResponseImpl response, boolean buffering) {
            this.response = response;
            this.buffering = buffering;
        }

        @Override
        public void write(int i) throws IOException {
            write(new byte[]{(byte) i});
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            try {
                validAsyncIOWriter(response.uuid());
                writeStatusAndHeaders(response);

                forceAsyncIOWriter = false;
                if (buffering && !ResponseWriter.this.completed()) {
                    writeWithBuffering(response, bytes);
                } else {
                    writer().write(response, bytes);
                }
            } catch (IOException e) {
                handleException(response, e);
                throw e;
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void write(byte[] bytes, int start, int offset) throws IOException {
            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            try {
                validAsyncIOWriter(response.uuid());
                writeStatusAndHeaders(response);

                forceAsyncIOWriter = false;
                if (buffering && !ResponseWriter.this.completed()) {
                    byte[] copy = new byte[offset];
                    System.arraycopy(bytes, start, copy, 0, offset);
                    writeWithBuffering(response, copy);
                } else {
                    writer().write(response, bytes, start, offset);
                }
            } catch (IOException e) {
                handleException(response, e);
                throw e;
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void flush() throws IOException {
            if (!validFlushOrClose(response.uuid())) return;

            writeStatusAndHeaders(response);

            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            try {
                writer().flush(response);
            } catch (IOException e) {
                handleException(response, e);
                throw e;
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void close() throws IOException {
            ResponseWriter.this.onComplete(response);
            if (!validFlushOrClose(response.uuid())
                    || writer() instanceof KeepOpenStreamAware) return;

            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            try {
                if (buffering && !ResponseWriter.this.completed()) {
                    writeWithBuffering(response, null);
                }
                writer().close(response);
            } catch (IOException e) {
                handleException(response, e);
                throw e;
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }
    }

    // -- Inner class: Writer --

    private final class Writer extends PrintWriter {
        private final AtmosphereResponseImpl response;

        Writer(AtmosphereResponseImpl response, OutputStream out) {
            super(out);
            this.response = response;
        }

        @Override
        public void write(char[] chars, int offset, int lenght) {
            boolean b = forceAsyncIOWriter;
            try {
                validAsyncIOWriter(response.uuid());

                // Prevent StackOverflow
                writeStatusAndHeaders(response);
                forceAsyncIOWriter = false;
                writer().write(response, new String(chars, offset, lenght));
            } catch (IOException e) {
                handleException(response, e);
                throw new RuntimeException("I/O write failure", e);
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void write(char[] chars) {
            boolean b = forceAsyncIOWriter;
            try {
                validAsyncIOWriter(response.uuid());

                writeStatusAndHeaders(response);
                // Prevent StackOverflow
                forceAsyncIOWriter = false;
                writer().write(response, new String(chars));
            } catch (IOException e) {
                handleException(response, e);
                throw new RuntimeException("I/O write failure", e);
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void write(String s, int offset, int lenght) {
            boolean b = forceAsyncIOWriter;

            try {
                validAsyncIOWriter(response.uuid());

                writeStatusAndHeaders(response);
                // Prevent StackOverflow
                forceAsyncIOWriter = false;
                writer().write(response, s.substring(offset, lenght));
            } catch (IOException e) {
                handleException(response, e);
                throw new RuntimeException("I/O write failure", e);
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void write(String s) {

            boolean b = forceAsyncIOWriter;
            try {
                validAsyncIOWriter(response.uuid());

                writeStatusAndHeaders(response);
                // Prevent StackOverflow
                forceAsyncIOWriter = false;
                writer().write(response, s);
            } catch (IOException e) {
                handleException(response, e);
                throw new RuntimeException("I/O write failure", e);
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void flush() {
            if (!validFlushOrClose(response.uuid())) return;

            boolean b = forceAsyncIOWriter;
            try {
                writeStatusAndHeaders(response);
                // Prevent StackOverflow
                forceAsyncIOWriter = false;
                writer().flush(response);
            } catch (IOException e) {
                handleException(response, e);
            } finally {
                forceAsyncIOWriter = b;
            }
        }

        @Override
        public void close() {
            if (!validFlushOrClose(response.uuid())
                    || writer() instanceof KeepOpenStreamAware) return;

            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            try {
                writer().close(response);
            } catch (IOException e) {
                handleException(response, e);
            } finally {
                forceAsyncIOWriter = b;
            }
        }
    }
}
