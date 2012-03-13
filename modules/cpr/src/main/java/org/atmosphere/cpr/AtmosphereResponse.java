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


import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An Atmosphere's response representation. An AtmosphereResponse can be used to construct bi-directional asynchronous
 * application. If the underlying transport is a WebSocket or if its associated {@link AtmosphereResource} has been
 * suspended, this object can be used to write message back tp the client at any moment.
 * <br/>
 * This object can delegates the write operation to {@link AsyncIOWriter}. An {@link AsyncProtocol} can also be
 * consulted before the bytes/string write process gets delegated to an {@link AsyncIOWriter}. If {@link org.atmosphere.cpr.AsyncProtocol#inspectResponse()}
 * return true, the {@link org.atmosphere.cpr.AsyncProtocol#handleResponse(AtmosphereResponse, String)} will have a chance to
 * manipulate the bytes and return a new representation. That new representation will then be delegated to an
 * {@link AsyncIOWriter}.
 */
public class AtmosphereResponse extends HttpServletResponseWrapper {

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
    private AsyncProtocol asyncProtocol = new FakeAsyncProtocol();
    private boolean headerHandled = false;
    private AtmosphereRequest atmosphereRequest;
    private static final DummyHttpServletResponse dsr = new DummyHttpServletResponse();
    private final AtomicBoolean writeStatusAndHeader = new AtomicBoolean(false);
    private final boolean delegateToNativeResponse;
    private final boolean destroyable;

    public AtmosphereResponse(AsyncIOWriter asyncIOWriter, AsyncProtocol asyncProtocol, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(dsr);
        this.asyncIOWriter = asyncIOWriter;
        this.asyncProtocol = asyncProtocol;
        this.atmosphereRequest = atmosphereRequest;
        this.writeStatusAndHeader.set(false);
        this.headers = new HashMap<String, String>();
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = destroyable;
    }

    public AtmosphereResponse(HttpServletResponse r, AsyncIOWriter asyncIOWriter, AsyncProtocol asyncProtocol, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(r);
        this.asyncIOWriter = asyncIOWriter;
        this.asyncProtocol = asyncProtocol;
        this.atmosphereRequest = atmosphereRequest;
        this.writeStatusAndHeader.set(false);
        this.headers = new HashMap<String, String>();
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = destroyable;
    }

    private AtmosphereResponse(Builder b) {
        super(b.atmosphereResponse);
        this.asyncIOWriter = b.asyncIOWriter;
        this.asyncProtocol = b.asyncProtocol;
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
        private AsyncProtocol asyncProtocol = new FakeAsyncProtocol();
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

        public Builder asyncProtocol(AsyncProtocol asyncProtocol) {
            this.asyncProtocol = asyncProtocol;
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
        return HttpServletResponse.class.cast(getResponse());
    }

    public void destroy() {
        if (!destroyable) return;
        cookies.clear();
        headers.clear();
        atmosphereRequest = null;
        asyncIOWriter = null;
        asyncProtocol = null;
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
    public void sendError(int sc, String msg) throws IOException {
        if (!delegateToNativeResponse) {
            setStatus(sc, msg);
            asyncIOWriter.writeError(sc, msg);
        } else {
            _r().sendError(sc, msg);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendError(int sc) throws IOException {
        if (!delegateToNativeResponse) {
            setStatus(sc);
            asyncIOWriter.writeError(sc, "");
        } else {
            _r().sendError(sc);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendRedirect(String location) throws IOException {
        if (!delegateToNativeResponse) {
            asyncIOWriter.redirect(location);
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
    public String getHeader(String name) {
        return headers.get(name);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<String> getHeaders(String name) {
        ArrayList<String> s = new ArrayList<String>();
        s.add(headers.get(name));
        return Collections.unmodifiableList(s);
    }

    /**
     * {@inheritDoc}
     */
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
            super.setCharacterEncoding(charSet);
        }
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

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (!delegateToNativeResponse) {
            return new ServletOutputStream() {

                @Override
                public void write(int i) throws java.io.IOException {
                    writeStatusAndHeaders();
                    if (asyncProtocol.inspectResponse()) {
                        asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new byte[]{(byte) i}, 0, 1));
                    } else {
                        asyncIOWriter.write(new byte[]{(byte) i});
                    }
                }

                @Override
                public void write(byte[] bytes) throws java.io.IOException {
                    writeStatusAndHeaders();
                    if (asyncProtocol.inspectResponse()) {
                        asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, bytes, 0, bytes.length));
                    } else {
                        asyncIOWriter.write(bytes);
                    }
                }

                @Override
                public void write(byte[] bytes, int start, int offset) throws java.io.IOException {
                    writeStatusAndHeaders();
                    if (asyncProtocol.inspectResponse()) {
                        byte[] b = asyncProtocol.handleResponse(AtmosphereResponse.this, bytes, start, offset);
                        asyncIOWriter.write(b, 0, b.length);
                    } else {
                        asyncIOWriter.write(bytes, start, offset);
                    }
                }

                @Override
                public void flush() throws IOException {
                    asyncIOWriter.flush();
                }

                @Override
                public void close() throws java.io.IOException {
                    asyncIOWriter.close();
                }
            };
        } else {
            return _r().getOutputStream();
        }
    }

    private void writeStatusAndHeaders() throws java.io.IOException {
        if (writeStatusAndHeader.getAndSet(false)) {
            asyncIOWriter.write(constructStatusAndHeaders());
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
        if (!delegateToNativeResponse) {
            return new PrintWriter(getOutputStream()) {
                public void write(char[] chars, int offset, int lenght) {
                    try {
                        writeStatusAndHeaders();
                        if (asyncProtocol.inspectResponse()) {
                            asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(chars, offset, lenght)));
                        } else {
                            asyncIOWriter.write(new String(chars, offset, lenght));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void write(char[] chars) {
                    try {
                        writeStatusAndHeaders();
                        if (asyncProtocol.inspectResponse()) {
                            asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(chars)));
                        } else {
                            asyncIOWriter.write(new String(chars));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void write(String s, int offset, int lenght) {
                    try {
                        writeStatusAndHeaders();
                        if (asyncProtocol.inspectResponse()) {
                            asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(s.substring(offset, lenght))));
                        } else {
                            asyncIOWriter.write(new String(s.substring(offset, lenght)));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void write(java.lang.String s) {
                    try {
                        writeStatusAndHeaders();
                        if (asyncProtocol.inspectResponse()) {
                            asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(s)));
                        } else {
                            asyncIOWriter.write(new String(s));
                        }
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
     * Return the associated {@link AtmosphereRequest}
     *
     * @return the associated {@link AtmosphereRequest}
     */
    public AtmosphereRequest getRequest() {
        return atmosphereRequest;
    }

    public void close() throws IOException {
        if (asyncIOWriter != null) {
            asyncIOWriter.close();
        }
    }

    private final static class DummyHttpServletResponse implements HttpServletResponse {
        public void addCookie(Cookie cookie) {
            throw new UnsupportedOperationException();
        }

        public boolean containsHeader(String name) {
            throw new UnsupportedOperationException();
        }

        public String encodeURL(String url) {
            throw new UnsupportedOperationException();
        }

        public String encodeRedirectURL(String url) {
            throw new UnsupportedOperationException();
        }

        public String encodeUrl(String url) {
            throw new UnsupportedOperationException();
        }

        public String encodeRedirectUrl(String url) {
            throw new UnsupportedOperationException();
        }

        public void sendError(int sc, String msg) throws IOException {
            throw new UnsupportedOperationException();
        }

        public void sendError(int sc) throws IOException {
            throw new UnsupportedOperationException();
        }

        public void sendRedirect(String location) throws IOException {
            throw new UnsupportedOperationException();
        }

        public void setDateHeader(String name, long date) {
            throw new UnsupportedOperationException();
        }

        public void addDateHeader(String name, long date) {
            throw new UnsupportedOperationException();
        }

        public void setHeader(String name, String value) {
            throw new UnsupportedOperationException();
        }

        public void addHeader(String name, String value) {
            throw new UnsupportedOperationException();
        }

        public void setIntHeader(String name, int value) {
            throw new UnsupportedOperationException();
        }

        public void addIntHeader(String name, int value) {
            throw new UnsupportedOperationException();
        }

        public void setStatus(int sc) {
            throw new UnsupportedOperationException();
        }

        public void setStatus(int sc, String sm) {
            throw new UnsupportedOperationException();
        }

        public int getStatus() {
            throw new UnsupportedOperationException();
        }

        public String getHeader(String name) {
            throw new UnsupportedOperationException();
        }

        public Collection<String> getHeaders(String name) {
            throw new UnsupportedOperationException();
        }

        public Collection<String> getHeaderNames() {
            throw new UnsupportedOperationException();
        }

        public String getCharacterEncoding() {
            throw new UnsupportedOperationException();
        }

        public String getContentType() {
            throw new UnsupportedOperationException();
        }

        public ServletOutputStream getOutputStream() throws IOException {
            throw new UnsupportedOperationException();
        }

        public PrintWriter getWriter() throws IOException {
            throw new UnsupportedOperationException();
        }

        public void setCharacterEncoding(String charset) {
            throw new UnsupportedOperationException();
        }

        public void setContentLength(int len) {
            throw new UnsupportedOperationException();
        }

        public void setContentType(String type) {
            throw new UnsupportedOperationException();
        }

        public void setBufferSize(int size) {
            throw new UnsupportedOperationException();
        }

        public int getBufferSize() {
            throw new UnsupportedOperationException();
        }

        public void flushBuffer() throws IOException {
            throw new UnsupportedOperationException();
        }

        public void resetBuffer() {
            throw new UnsupportedOperationException();
        }

        public boolean isCommitted() {
            throw new UnsupportedOperationException();
        }

        public void reset() {
            throw new UnsupportedOperationException();
        }

        public void setLocale(Locale loc) {
            throw new UnsupportedOperationException();
        }

        public Locale getLocale() {
            throw new UnsupportedOperationException();
        }
    }

    private final static class FakeAsyncProtocol implements AsyncProtocol {

        @Override
        public boolean inspectResponse() {
            return false;
        }

        @Override
        public String handleResponse(AtmosphereResponse res, String message) {
            return null;
        }

        @Override
        public byte[] handleResponse(AtmosphereResponse res, byte[] message, int offset, int length) {
            return new byte[0];
        }
    }

    /**
     * Wrap an {@link HttpServletResponse}
     * @param response  {@link HttpServletResponse}
     * @return  an {@link AtmosphereResponse}
     */
    public final static AtmosphereResponse wrap(HttpServletResponse response) {
        return new Builder().response(response).build();
    }
}
