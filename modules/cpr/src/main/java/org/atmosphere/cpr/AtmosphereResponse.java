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
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
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
 * This object can delegates the write operation to {@link AsyncIOWriter}. An {@link AsyncProtocol} can also be
 * consulted before the bytes/string write process gets delegated to an {@link AsyncIOWriter}. If {@link org.atmosphere.cpr.AsyncProtocol#inspectResponse()}
 * return true, the {@link org.atmosphere.cpr.AsyncProtocol#handleResponse(AtmosphereResponse, String)} will have a chance to
 * manipulate the bytes and return a new representation. That new representation will then be delegated to an
 * {@link AsyncIOWriter}.
 */
public class AtmosphereResponse implements HttpServletResponse {

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
    private AsyncProtocol asyncProtocol = new FakeAsyncProtocol();
    private boolean headerHandled = false;
    private AtmosphereRequest atmosphereRequest;
    private static final DummyHttpServletResponse dsr = new DummyHttpServletResponse();
    private final AtomicBoolean writeStatusAndHeader = new AtomicBoolean(false);
    private final boolean delegateToNativeResponse;
    private final boolean destroyable;
    private final HttpServletResponse response;
    private boolean forceAsyncIOWriter = false;

    public AtmosphereResponse(AsyncIOWriter asyncIOWriter, AsyncProtocol asyncProtocol, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        response = dsr;
        this.asyncIOWriter = asyncIOWriter;
        this.asyncProtocol = asyncProtocol;
        this.atmosphereRequest = atmosphereRequest;
        this.writeStatusAndHeader.set(false);
        this.headers = new HashMap<String, String>();
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = destroyable;
    }

    public AtmosphereResponse(HttpServletResponse r, AsyncIOWriter asyncIOWriter, AsyncProtocol asyncProtocol, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        response = r;
        this.asyncIOWriter = asyncIOWriter;
        this.asyncProtocol = asyncProtocol;
        this.atmosphereRequest = atmosphereRequest;
        this.writeStatusAndHeader.set(false);
        this.headers = new HashMap<String, String>();
        this.delegateToNativeResponse = asyncIOWriter == null;
        this.destroyable = destroyable;
    }

    private AtmosphereResponse(Builder b) {
        response = b.atmosphereResponse;
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
        return HttpServletResponse.class.cast(response);
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
        if (writeStatusAndHeader.getAndSet(false) && !forceAsyncIOWriter) {
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
        if (!delegateToNativeResponse || forceAsyncIOWriter) {
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

    public AtmosphereResponse request(AtmosphereRequest atmosphereRequest) {
        this.atmosphereRequest = atmosphereRequest;
        return this;
    }

    public void close() throws IOException {
        if (asyncIOWriter != null) {
            asyncIOWriter.close();
        }
    }

    public void closeStreamOrWriter() {
        try {
            boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
            if (isUsingStream) {
                response.getOutputStream().close();
            } else {
                response.getWriter().close();
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
    }


    public void write(String data) {
        boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
        try {
            if (isUsingStream) {
                response.getOutputStream().write(data.getBytes(getCharacterEncoding()));

            } else {
                response.getWriter().write(data);
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
    }

    public void write(byte[] data) {
        boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
        try {
            if (isUsingStream) {
                response.getOutputStream().write(data);

            } else {
                response.getWriter().write(new String(data, getCharacterEncoding()));
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
    }

    public void write(byte[] data, int offset, int length) {
        boolean isUsingStream = (Boolean) request().getAttribute(PROPERTY_USE_STREAM);
        try {
            if (isUsingStream) {
                response.getOutputStream().write(data, offset, length);

            } else {
                response.getWriter().write(new String(data,  offset, length, getCharacterEncoding()));
            }
        } catch (IOException e) {
            logger.trace("", e);
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
     * Wrap an {@link HttpServletResponse}
     * @param response  {@link HttpServletResponse}
     * @return  an {@link AtmosphereResponse}
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
        if (asyncProtocol != null ? !asyncProtocol.equals(that.asyncProtocol) : that.asyncProtocol != null)
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
        int result = cookies != null ? cookies.hashCode() : 0;
        result = 31 * result + (headers != null ? headers.hashCode() : 0);
        result = 31 * result + (asyncIOWriter != null ? asyncIOWriter.hashCode() : 0);
        result = 31 * result + status;
        result = 31 * result + (statusMessage != null ? statusMessage.hashCode() : 0);
        result = 31 * result + (charSet != null ? charSet.hashCode() : 0);
        result = 31 * result + (int) (contentLength ^ (contentLength >>> 32));
        result = 31 * result + (contentType != null ? contentType.hashCode() : 0);
        result = 31 * result + (isCommited ? 1 : 0);
        result = 31 * result + (locale != null ? locale.hashCode() : 0);
        result = 31 * result + (asyncProtocol != null ? asyncProtocol.hashCode() : 0);
        result = 31 * result + (headerHandled ? 1 : 0);
        result = 31 * result + (atmosphereRequest != null ? atmosphereRequest.hashCode() : 0);
        result = 31 * result + (writeStatusAndHeader != null ? writeStatusAndHeader.hashCode() : 0);
        result = 31 * result + (delegateToNativeResponse ? 1 : 0);
        result = 31 * result + (destroyable ? 1 : 0);
        result = 31 * result + (response != null ? response.hashCode() : 0);
        return result;
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
                ", asyncProtocol=" + asyncProtocol +
                ", headerHandled=" + headerHandled +
                ", atmosphereRequest=" + atmosphereRequest +
                ", writeStatusAndHeader=" + writeStatusAndHeader +
                ", delegateToNativeResponse=" + delegateToNativeResponse +
                ", destroyable=" + destroyable +
                ", response=" + response +
                '}';
    }
}