/*
 * Copyright 2012 Jeanfrancois Arcand
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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;

/**
 * An Atmosphere's response representation. An AtmosphereResponse can be used to construct bi-directional asynchronous
 * application. If the underlying transport is a WebSocket or if its associated {@link AtmosphereResource} has been
 * suspended, this object can be used to write message back tp the client at any moment.
 * <br/>
 * This object can delegates the write operation to {@link AsyncIOWriter}.
 */
public class AtmosphereResponse extends HttpServletResponseWrapper {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResponse.class);
    private final List<Cookie> cookies = new ArrayList<Cookie>();
    private final Map<String, String> headers;
    private AsyncIOWriter asyncIOWriter;
    private int status = 200;
    private String statusMessage = "OK";
    private String charSet = "UTF-8";
    private long contentLength = -1;
    private String contentType = "text/html";
    private boolean isCommited = false;
    private Locale locale;
    private boolean headerHandled = false;
    private AtmosphereRequest atmosphereRequest;
    private static final DummyHttpServletResponse dsr = new DummyHttpServletResponse();
    private final AtomicBoolean writeStatusAndHeader = new AtomicBoolean(false);
    private final boolean delegateToNativeResponse;
    private boolean destroyable;
    private HttpServletResponse response;
    private boolean forceAsyncIOWriter = false;

    public AtmosphereResponse(AsyncIOWriter asyncIOWriter, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(dsr);
        response = dsr;
        this.asyncIOWriter = asyncIOWriter;
        this.atmosphereRequest = atmosphereRequest;
        this.writeStatusAndHeader.set(false);
        this.headers = new HashMap<String, String>();
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = destroyable;
    }

    public AtmosphereResponse(HttpServletResponse r, AsyncIOWriter asyncIOWriter, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(r);
        response = r;
        this.asyncIOWriter = asyncIOWriter;
        this.atmosphereRequest = atmosphereRequest;
        this.writeStatusAndHeader.set(false);
        this.headers = new HashMap<String, String>();
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = destroyable;
    }

    private AtmosphereResponse(Builder b) {
        super(b.atmosphereResponse);

        response = b.atmosphereResponse;
        this.asyncIOWriter = b.asyncIOWriter;
        this.atmosphereRequest = b.atmosphereRequest;
        this.status = b.status;
        this.statusMessage = b.statusMessage;
        this.writeStatusAndHeader.set(b.writeStatusAndHeader.get());
        this.headers = b.headers;
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = b.destroyable;
    }

    public final static class Builder {
        private AsyncIOWriter asyncIOWriter;
        private int status = 200;
        private String statusMessage = "OK";
        private AtmosphereRequest atmosphereRequest;
        private HttpServletResponse atmosphereResponse = dsr;
        private AtomicBoolean writeStatusAndHeader = new AtomicBoolean(true);
        private final Map<String, String> headers = new HashMap<String, String>();
        public boolean destroyable = true;

        public Builder() {
        }

        public Builder destroyable(boolean isRecyclable) {
            this.destroyable = isRecyclable;
            return this;
        }

        public Builder asyncIOWriter(AsyncIOWriter asyncIOWriter) {
            this.asyncIOWriter = asyncIOWriter;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder statusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
            return this;
        }

        public Builder request(AtmosphereRequest atmosphereRequest) {
            this.atmosphereRequest = atmosphereRequest;
            return this;
        }

        public AtmosphereResponse build() {
            return new AtmosphereResponse(this);
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder writeHeader(boolean writeStatusAndHeader) {
            this.writeStatusAndHeader.set(writeStatusAndHeader);
            return this;
        }

        public Builder response(HttpServletResponse res) {
            this.atmosphereResponse = res;
            return this;
        }
    }

    private HttpServletResponse _r() {
        return HttpServletResponse.class.cast(response);
    }

    public void destroy() {
        if (!destroyable) return;
        cookies.clear();
        headers.clear();
        atmosphereRequest = null;
        asyncIOWriter = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addCookie(Cookie cookie) {
        if (delegateToNativeResponse) {
            _r().addCookie(cookie);
        } else {
            cookies.add(cookie);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsHeader(String name) {
        return !delegateToNativeResponse ? (headers.get(name) == null ? false : true) : _r().containsHeader(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String encodeURL(String url) {
        return response.encodeURL(url);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String encodeRedirectURL(String url) {
        return response.encodeRedirectURL(url);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String encodeUrl(String url) {
        return response.encodeURL(url);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String encodeRedirectUrl(String url) {
        return response.encodeRedirectURL(url);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendError(int sc, String msg) throws IOException {
        if (!delegateToNativeResponse || forceAsyncIOWriter) {
            setStatus(sc, msg);

            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            asyncIOWriter.writeError(this, sc, msg);
            forceAsyncIOWriter = b;
        } else {
            if (!_r().isCommitted()) {
                _r().sendError(sc, msg);
            } else {
                logger.warn("Committed error code {} {}", sc, msg);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendError(int sc) throws IOException {
        if (!delegateToNativeResponse || forceAsyncIOWriter) {
            setStatus(sc);
            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            asyncIOWriter.writeError(this, sc, "");
            forceAsyncIOWriter = b;
        } else {
            if (!_r().isCommitted()) {
                _r().sendError(sc);
            } else {
                logger.warn("Committed error code {}", sc);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendRedirect(String location) throws IOException {
        if (!delegateToNativeResponse || forceAsyncIOWriter) {

            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            asyncIOWriter.redirect(this, location);
            forceAsyncIOWriter = b;
        } else {
            _r().sendRedirect(location);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDateHeader(String name, long date) {
        if (!delegateToNativeResponse) {
            headers.put(name, String.valueOf(date));
        } else {
            _r().setDateHeader(name, date);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDateHeader(String name, long date) {
        if (!delegateToNativeResponse) {
            headers.put(name, String.valueOf(date));
        } else {
            _r().setDateHeader(name, date);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHeader(String name, String value) {
        if (!delegateToNativeResponse) {
            headers.put(name, value);
        } else {
            _r().setHeader(name, value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addHeader(String name, String value) {
        if (!delegateToNativeResponse) {
            headers.put(name, value);
        } else {
            _r().addHeader(name, value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setIntHeader(String name, int value) {
        if (!delegateToNativeResponse) {
            headers.put(name, String.valueOf(value));
        } else {
            _r().setIntHeader(name, value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addIntHeader(String name, int value) {
        if (!delegateToNativeResponse) {
            headers.put(name, String.valueOf(value));
        } else {
            _r().addIntHeader(name, value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStatus(int status) {
        if (!delegateToNativeResponse) {
            this.status = status;
        } else {
            _r().setStatus(status);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStatus(int status, String statusMessage) {
        if (!delegateToNativeResponse) {
            this.statusMessage = statusMessage;
            this.status = status;
        } else {
            _r().setStatus(status, statusMessage);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, String> headers() {
        if (!headerHandled) {
            for (Cookie c : cookies) {
                headers.put("Set-Cookie", c.toString());
            }
            headerHandled = false;
        }
        return headers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHeader(String name) {
        if (name.equalsIgnoreCase("content-type")) {
            String s = headers.get("Content-Type");
            return s == null ? contentType : s;
        }
        return headers.get(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getHeaders(String name) {
        ArrayList<String> s = new ArrayList<String>();
        String h;
        if (name.equalsIgnoreCase("content-type")) {
            h = headers.get("Content-Type");
        } else {
            h = headers.get(name);
        }
        s.add(h);
        return Collections.unmodifiableList(s);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableSet(headers.keySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCharacterEncoding(String charSet) {
        if (!delegateToNativeResponse) {
            this.charSet = charSet;
        } else {
            response.setCharacterEncoding(charSet);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flushBuffer() throws IOException {
        response.flushBuffer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getBufferSize() {
        return response.getBufferSize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCharacterEncoding() {
        if (!delegateToNativeResponse) {
            return charSet;
        } else {
            return _r().getCharacterEncoding();
        }
    }

    /**
     * Can this object be destroyed. Default is true.
     */
    public boolean isDestroyable() {
        return destroyable;
    }

    public AtmosphereResponse destroyable(boolean destroyable) {
        this.destroyable = destroyable;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (!delegateToNativeResponse || forceAsyncIOWriter) {
            return new ServletOutputStream() {

                @Override
                public void write(int i) throws java.io.IOException {
                    writeStatusAndHeaders();

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    asyncIOWriter.write(AtmosphereResponse.this, new byte[]{(byte) i});
                    forceAsyncIOWriter = b;
                }

                @Override
                public void write(byte[] bytes) throws java.io.IOException {
                    writeStatusAndHeaders();

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    asyncIOWriter.write(AtmosphereResponse.this, bytes);
                    forceAsyncIOWriter = b;
                }

                @Override
                public void write(byte[] bytes, int start, int offset) throws java.io.IOException {
                    writeStatusAndHeaders();

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    asyncIOWriter.write(AtmosphereResponse.this, bytes, start, offset);
                    forceAsyncIOWriter = b;
                }

                @Override
                public void flush() throws IOException {
                    writeStatusAndHeaders();

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    asyncIOWriter.flush(AtmosphereResponse.this);
                    forceAsyncIOWriter = b;

                }

                @Override
                public void close() throws java.io.IOException {

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    asyncIOWriter.close(AtmosphereResponse.this);
                    forceAsyncIOWriter = b;
                }
            };
        } else {
            return _r().getOutputStream();
        }
    }

    private void writeStatusAndHeaders() throws java.io.IOException {
        if (writeStatusAndHeader.getAndSet(false) && !forceAsyncIOWriter) {
            asyncIOWriter.write(this, constructStatusAndHeaders());
        }
    }

    private String constructStatusAndHeaders() {
        StringBuffer b = new StringBuffer("HTTP/1.1")
                .append(" ")
                .append(status)
                .append(" ")
                .append(statusMessage)
                .append("\n");

        b.append("Content-Type").append(":").append(headers.get("Content-Type") == null ? contentType : headers.get("Content-Type")).append("\n");
        if (contentLength != -1) {
            b.append("Content-Length").append(":").append(contentLength).append("\n");
        }

        for (String s : headers().keySet()) {
            if (!s.equalsIgnoreCase("Content-Type")) {
                b.append(s).append(":").append(headers.get(s)).append("\n");
            }
        }
        b.deleteCharAt(b.length() - 1);
        b.append("\r\n\r\n");
        return b.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PrintWriter getWriter() throws IOException {
        if (!delegateToNativeResponse || forceAsyncIOWriter) {
            return new PrintWriter(getOutputStream()) {
                public void write(char[] chars, int offset, int lenght) {
                    try {
                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        boolean b = forceAsyncIOWriter;
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(chars, offset, lenght));
                        forceAsyncIOWriter = b;

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void write(char[] chars) {
                    try {
                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        boolean b = forceAsyncIOWriter;
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(chars));
                        forceAsyncIOWriter = b;

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void write(String s, int offset, int lenght) {
                    try {
                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        boolean b = forceAsyncIOWriter;
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(s.substring(offset, lenght)));
                        forceAsyncIOWriter = b;

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void write(java.lang.String s) {
                    try {
                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        boolean b = forceAsyncIOWriter;
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(s));
                        forceAsyncIOWriter = b;

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        } else {
            return _r().getWriter();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContentLength(int len) {
        if (!delegateToNativeResponse) {
            contentLength = len;
        } else {
            _r().setContentLength(len);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContentType(String contentType) {
        if (!delegateToNativeResponse) {
            this.contentType = contentType;
        } else {
            _r().setContentType(contentType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getContentType() {
        if (!delegateToNativeResponse) {
            return contentType;
        } else {
            return _r().getContentType();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCommitted() {
        if (!delegateToNativeResponse) {
            return isCommited;
        } else {
            return _r().isCommitted();
        }
    }

    @Override
    public void reset() {
        response.reset();
    }

    @Override
    public void resetBuffer() {
        response.resetBuffer();
    }

    @Override
    public void setBufferSize(int size) {
        response.setBufferSize(size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLocale(Locale locale) {
        if (!delegateToNativeResponse) {
            this.locale = locale;
        } else {
            _r().setLocale(locale);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Locale getLocale() {
        if (!delegateToNativeResponse) {
            return locale;
        } else {
            return _r().getLocale();
        }
    }

    /**
     * Return the underlying {@link AsyncIOWriter}
     */
    public AsyncIOWriter getAsyncIOWriter() {
        return asyncIOWriter;
    }

    /**
     * Set an implementation of {@link AsyncIOWriter} that will be invoked every time a write operation is ready to be
     * processed.
     *
     * @param asyncIOWriter of {@link AsyncIOWriter}
     * @return this
     */
    public AtmosphereResponse asyncIOWriter(AsyncIOWriter asyncIOWriter) {
        this.asyncIOWriter = asyncIOWriter;
        forceAsyncIOWriter = true;
        return this;
    }

    /**
     * Return the associated {@link AtmosphereRequest}
     *
     * @return the associated {@link AtmosphereRequest}
     */
    public AtmosphereRequest request() {
        return atmosphereRequest;
    }

    /**
     * Set the associated {@link AtmosphereRequest}
     *
     * @param atmosphereRequest a {@link AtmosphereRequest}
     * @return this
     */
    public AtmosphereResponse request(AtmosphereRequest atmosphereRequest) {
        this.atmosphereRequest = atmosphereRequest;
        return this;
    }

    /**
     * Close the associated {@link AsyncIOWriter}
     *
     * @throws IOException
     */
    public void close() throws IOException {
        if (asyncIOWriter != null) {
            asyncIOWriter.close(this);
        }
    }

    /**
     * Close the associated {@link PrintWriter} or {@link java.io.OutputStream}
     */
    public void closeStreamOrWriter() {
        try {
            boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
            if (isUsingStream) {
                try {
                    getOutputStream().close();
                } catch (java.lang.IllegalStateException ex) {
                }
            } else {
                getWriter().close();
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
    }

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}
     *
     * @param data the String to write
     */
    public AtmosphereResponse write(String data) {
        boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
        try {
            if (isUsingStream) {
                try {
                    getOutputStream().write(data.getBytes(getCharacterEncoding()));
                } catch (java.lang.IllegalStateException ex) {
                    ex.printStackTrace();
                }
            } else {
                getWriter().write(data);
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
        return this;
    }

    /**
     * Write the bytes by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}
     *
     * @param data the bytes to write
     */
    public AtmosphereResponse write(byte[] data) {
        boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
        try {
            if (isUsingStream) {
                try {
                    getOutputStream().write(data);
                } catch (java.lang.IllegalStateException ex) {
                }
            } else {
                getWriter().write(new String(data, getCharacterEncoding()));
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
        return this;
    }

    /**
     * Write the bytes by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}
     *
     * @param data   the bytes to write
     * @param offset the first byte position to write
     * @param length the data length
     */
    public AtmosphereResponse write(byte[] data, int offset, int length) {
        boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
        try {
            if (isUsingStream) {
                try {
                    getOutputStream().write(data, offset, length);
                } catch (java.lang.IllegalStateException ex) {
                }
            } else {
                getWriter().write(new String(data, offset, length, getCharacterEncoding()));
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
        return this;
    }

    /**
     * The {@link AtmosphereResource} associated with this request. If the request hasn't been suspended, this
     * method will return null.
     *
     * @return an {@link AtmosphereResource}, or null.
     */
    public AtmosphereResource resource() {
        if (atmosphereRequest != null) {
            return (AtmosphereResource) atmosphereRequest.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
        } else {
            return null;
        }
    }

    public void setResponse(ServletResponse response) {
        super.setResponse(response);
        if (HttpServletResponse.class.isAssignableFrom(response.getClass())) {
            this.response = HttpServletResponse.class.cast(response);
        }
    }

    private final static class DummyHttpServletResponse implements HttpServletResponse {
        public void addCookie(Cookie cookie) {
            logger.trace("Unsupported");
        }

        public boolean containsHeader(String name) {
            logger.trace("Unsupported");
            return false;
        }

        public String encodeURL(String url) {
            logger.trace("Unsupported");
            return url;
        }

        public String encodeRedirectURL(String url) {
            logger.trace("Unsupported");
            return url;
        }

        public String encodeUrl(String url) {
            logger.trace("Unsupported");
            return url;
        }

        public String encodeRedirectUrl(String url) {
            logger.trace("Unsupported");
            return url;
        }

        public void sendError(int sc, String msg) throws IOException {
            logger.trace("Unsupported");
        }

        public void sendError(int sc) throws IOException {
            logger.trace("Unsupported");
        }

        public void sendRedirect(String location) throws IOException {
            logger.trace("Unsupported");
        }

        public void setDateHeader(String name, long date) {
            logger.trace("Unsupported");
        }

        public void addDateHeader(String name, long date) {
            logger.trace("Unsupported");
        }

        public void setHeader(String name, String value) {
            logger.trace("Unsupported");
        }

        public void addHeader(String name, String value) {
            logger.trace("Unsupported");
        }

        public void setIntHeader(String name, int value) {
            logger.trace("Unsupported");
        }

        public void addIntHeader(String name, int value) {
            logger.trace("Unsupported");
        }

        public void setStatus(int sc) {
            logger.trace("Unsupported");
        }

        public void setStatus(int sc, String sm) {
            logger.trace("Unsupported");
        }

        public int getStatus() {
            logger.trace("Unsupported");
            return 200;
        }

        public String getHeader(String name) {
            logger.trace("Unsupported");
            return null;
        }

        public Collection<String> getHeaders(String name) {
            logger.trace("Unsupported");
            return Collections.emptyList();
        }

        public Collection<String> getHeaderNames() {
            logger.trace("Unsupported");
            return Collections.emptyList();
        }

        public String getCharacterEncoding() {
            logger.trace("Unsupported");
            return "ISO-8859-1";
        }

        public String getContentType() {
            logger.trace("Unsupported");
            return "text/plain";
        }

        public ServletOutputStream getOutputStream() throws IOException {
            logger.trace("Unsupported");
            return new NoOpsOutputStream();
        }

        public PrintWriter getWriter() throws IOException {
            logger.trace("Unsupported");
            return new PrintWriter(new NoOpsPrintWriter());
        }

        public void setCharacterEncoding(String charset) {
            logger.trace("Unsupported");
        }

        public void setContentLength(int len) {
            logger.trace("Unsupported");
        }

        public void setContentType(String type) {
            logger.trace("Unsupported");
        }

        public void setBufferSize(int size) {
            logger.trace("Unsupported");
        }

        public int getBufferSize() {
            logger.trace("Unsupported");
            return -1;
        }

        public void flushBuffer() throws IOException {
            logger.trace("Unsupported");
        }

        public void resetBuffer() {
            logger.trace("Unsupported");
        }

        public boolean isCommitted() {
            logger.trace("Unsupported");
            return false;
        }

        public void reset() {
            logger.trace("Unsupported");
        }

        public void setLocale(Locale loc) {
            logger.trace("Unsupported");
        }

        public Locale getLocale() {
            logger.trace("Unsupported");
            return Locale.ENGLISH;
        }
    }

    private final static class NoOpsOutputStream extends ServletOutputStream {
        @Override
        public void write(int i) throws IOException {
        }
    }

    private final static class NoOpsPrintWriter extends Writer {

        @Override
        public void write(char[] chars, int i, int i1) throws IOException {
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }
    }

    /**
     * Create an instance not associated with any response parent.
     * @return
     */
    public final static AtmosphereResponse create() {
        return new Builder().build();
    }

    /**
     * Wrap an {@link HttpServletResponse}
     *
     * @param response {@link HttpServletResponse}
     * @return an {@link AtmosphereResponse}
     */
    public final static AtmosphereResponse wrap(HttpServletResponse response) {
        return new Builder().response(response).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AtmosphereResponse that = (AtmosphereResponse) o;

        if (contentLength != that.contentLength) return false;
        if (delegateToNativeResponse != that.delegateToNativeResponse) return false;
        if (destroyable != that.destroyable) return false;
        if (headerHandled != that.headerHandled) return false;
        if (isCommited != that.isCommited) return false;
        if (status != that.status) return false;
        if (asyncIOWriter != null ? !asyncIOWriter.equals(that.asyncIOWriter) : that.asyncIOWriter != null)
            return false;
        if (atmosphereRequest != null ? !atmosphereRequest.equals(that.atmosphereRequest) : that.atmosphereRequest != null)
            return false;
        if (charSet != null ? !charSet.equals(that.charSet) : that.charSet != null) return false;
        if (contentType != null ? !contentType.equals(that.contentType) : that.contentType != null) return false;
        if (cookies != null ? !cookies.equals(that.cookies) : that.cookies != null) return false;
        if (headers != null ? !headers.equals(that.headers) : that.headers != null) return false;
        if (locale != null ? !locale.equals(that.locale) : that.locale != null) return false;
        if (response != null ? !response.equals(that.response) : that.response != null) return false;
        if (statusMessage != null ? !statusMessage.equals(that.statusMessage) : that.statusMessage != null)
            return false;
        if (writeStatusAndHeader != null ? !writeStatusAndHeader.equals(that.writeStatusAndHeader) : that.writeStatusAndHeader != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return resource() != null ? Integer.valueOf(resource().uuid()) : super.hashCode();
    }

    @Override
    public String toString() {
        return "AtmosphereResponse{" +
                "cookies=" + cookies +
                ", headers=" + headers +
                ", asyncIOWriter=" + asyncIOWriter +
                ", status=" + status +
                ", statusMessage='" + statusMessage + '\'' +
                ", charSet='" + charSet + '\'' +
                ", contentLength=" + contentLength +
                ", contentType='" + contentType + '\'' +
                ", isCommited=" + isCommited +
                ", locale=" + locale +
                ", headerHandled=" + headerHandled +
                ", atmosphereRequest=" + atmosphereRequest +
                ", writeStatusAndHeader=" + writeStatusAndHeader +
                ", delegateToNativeResponse=" + delegateToNativeResponse +
                ", destroyable=" + destroyable +
                ", response=" + response +
                '}';
    }
}