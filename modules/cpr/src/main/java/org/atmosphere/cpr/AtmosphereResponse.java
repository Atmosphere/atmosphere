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
package org.atmosphere.cpr;

import org.atmosphere.util.CookieUtil;
import org.atmosphere.util.ServletProxyFactory;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;

/**
 * An Atmosphere's response representation. An AtmosphereResponse can be used to construct a bi-directional asynchronous
 * application. If the underlying transport is a WebSocket or if its associated {@link AtmosphereResource} has been
 * suspended, this object can be used to write message back to the client at any moment.
 * <br/>
 * This object can delegate the write operation to {@link AsyncIOWriter}.
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
    private static final HttpServletResponse dsr = (HttpServletResponse)
            Proxy.newProxyInstance(AtmosphereResponse.class.getClassLoader(), new Class[]{HttpServletResponse.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            return ServletProxyFactory.getDefault().proxy(proxy, method, args);
                        }
                    });
    private final AtomicBoolean writeStatusAndHeader = new AtomicBoolean(false);
    private boolean delegateToNativeResponse;
    private boolean destroyable;
    private HttpServletResponse response;
    private boolean forceAsyncIOWriter = false;
    private String uuid = "0";
    private final AtomicBoolean usingStream = new AtomicBoolean(true);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

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
        private boolean destroyable = true;

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
        destroy(destroyable);
    }

    public void destroy(boolean force) {
        if (!force) return;
        logger.trace("{} destroyed", uuid);
        cookies.clear();
        headers.clear();
        atmosphereRequest = null;
        asyncIOWriter = null;
        destroyed.set(true);
    }

    public boolean destroyed() {
        return destroyed.get();
    }

    @Override
    public void addCookie(Cookie cookie) {
        if (delegateToNativeResponse) {
            _r().addCookie(cookie);
        } else {
            cookies.add(cookie);
        }
    }

    @Override
    public boolean containsHeader(String name) {
        return !delegateToNativeResponse ? (headers.get(name) == null ? false : true) : _r().containsHeader(name);
    }

    @Override
    public String encodeURL(String url) {
        return response.encodeURL(url);
    }

    @Override
    public String encodeRedirectURL(String url) {
        return response.encodeRedirectURL(url);
    }

    @Override
    public String encodeUrl(String url) {
        return response.encodeURL(url);
    }

    @Override
    public String encodeRedirectUrl(String url) {
        return response.encodeRedirectURL(url);
    }

    public AtmosphereResponse delegateToNativeResponse(boolean delegateToNativeResponse) {
        this.delegateToNativeResponse = delegateToNativeResponse;
        return this;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        if (forceAsyncIOWriter || !delegateToNativeResponse) {
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

    @Override
    public void sendError(int sc) throws IOException {
        if (forceAsyncIOWriter || !delegateToNativeResponse) {
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

    @Override
    public void sendRedirect(String location) throws IOException {
        if (forceAsyncIOWriter || !delegateToNativeResponse) {

            // Prevent StackOverflow
            boolean b = forceAsyncIOWriter;
            forceAsyncIOWriter = false;
            asyncIOWriter.redirect(this, location);
            forceAsyncIOWriter = b;
        } else {
            _r().sendRedirect(location);
        }
    }

    @Override
    public void setDateHeader(String name, long date) {
        if (!delegateToNativeResponse) {
            headers.put(name, String.valueOf(date));
        } else {
            _r().setDateHeader(name, date);
        }
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (!delegateToNativeResponse) {
            headers.put(name, String.valueOf(date));
        } else {
            _r().setDateHeader(name, date);
        }
    }

    @Override
    public void setHeader(String name, String value) {
        //https://github.com/Atmosphere/atmosphere/issues/1783
        if (value == null) value = "";

        headers.put(name, value);

        if (delegateToNativeResponse) {
            _r().setHeader(name, value);
        }

        if (name.equalsIgnoreCase(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)) {
            uuid = value;
        }
    }

    @Override
    public void addHeader(String name, String value) {
        //https://github.com/Atmosphere/atmosphere/issues/1783
        if (value == null) value = "";

        headers.put(name, value);

        if (delegateToNativeResponse) {
            _r().addHeader(name, value);
        }
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, String.valueOf(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        setHeader(name, String.valueOf(value));
    }

    @Override
    public void setStatus(int status) {
        if (!delegateToNativeResponse) {
            this.status = status;
        } else {
            _r().setStatus(status);
        }
    }

    @Override
    public void setStatus(int status, String statusMessage) {
        if (!delegateToNativeResponse) {
            this.statusMessage = statusMessage;
            this.status = status;
        } else {
            _r().setStatus(status, statusMessage);
        }
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public ServletResponse getResponse() {
        if (Proxy.class.isAssignableFrom(response.getClass())) {
            return this;
        } else {
            return super.getResponse();
        }
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, String> headers() {
        if (!headerHandled) {
            for (Cookie c : cookies) {
                headers.put("Set-Cookie", CookieUtil.toString(c));
            }
            headerHandled = true;
        }
        return headers;
    }

    @Override
    public String getHeader(String name) {
        String s;
        if (name.equalsIgnoreCase("content-type")) {
            s = headers.get("Content-Type");

            if (s == null) {
                s = _r().getHeader(name);
            }

            return s == null ? contentType : s;
        }

        s = headers.get(name);
        if (s == null) {
            s = _r().getHeader(name);
        }

        return s;
    }

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

        Collection<String> r = _r().getHeaders(name);
        if (r != null && !r.isEmpty()) {
            s.addAll(r);
        }

        return Collections.unmodifiableList(s);
    }

    @Override
    public Collection<String> getHeaderNames() {
        Collection<String> r = _r().getHeaderNames();
        Set<String> s = headers.keySet();
        if (r != null && !r.isEmpty()) {
            // detach the keyset from the original hashmap
            s = new HashSet<String>(s);
            s.addAll(r);
        }

        return Collections.unmodifiableSet(s);
    }

    @Override
    public void setCharacterEncoding(String charSet) {
        if (!delegateToNativeResponse) {
            this.charSet = charSet;
        } else {
            _r().setCharacterEncoding(charSet);
        }
    }

    @Override
    public void flushBuffer() throws IOException {
        try {
            response.flushBuffer();
        } catch (IOException ex) {
            handleException(ex);
            throw ex;
        }
    }

    @Override
    public int getBufferSize() {
        return response.getBufferSize();
    }

    @Override
    public String getCharacterEncoding() {
        if (!delegateToNativeResponse) {
            return charSet;
        } else {
            return _r().getCharacterEncoding() == null ? charSet : _r().getCharacterEncoding();
        }
    }

    /**
     * Check if this object can be destroyed. Default is true.
     */
    public boolean isDestroyable() {
        return destroyable;
    }

    public AtmosphereResponse destroyable(boolean destroyable) {
        this.destroyable = destroyable;
        return this;
    }

    private void validAsyncIOWriter() throws IOException {
        if (asyncIOWriter == null) {
            logger.trace("{} invalid state", this.hashCode());
            throw new IOException("AtmosphereResource Cancelled: " + uuid);
        }
    }

    private boolean validFlushOrClose() {
        if (asyncIOWriter == null) {
            logger.warn("AtmosphereResponse for {} has been closed", uuid);
            return false;
        }
        return true;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (forceAsyncIOWriter || !delegateToNativeResponse) {
            return new ServletOutputStream() {

                @Override
                public void write(int i) throws java.io.IOException {
                    write(new byte[]{(byte) i});
                }

                @Override
                public void write(byte[] bytes) throws java.io.IOException {
                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    try {
                        validAsyncIOWriter();
                        writeStatusAndHeaders();

                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, bytes);
                    } catch (IOException e) {
                        handleException(e);
                        throw e;
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void write(byte[] bytes, int start, int offset) throws java.io.IOException {
                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    try {
                        validAsyncIOWriter();
                        writeStatusAndHeaders();

                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, bytes, start, offset);
                    } catch (IOException e) {
                        handleException(e);
                        throw e;
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void flush() throws IOException {
                    if (!validFlushOrClose()) return;

                    writeStatusAndHeaders();

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    try {
                        asyncIOWriter.flush(AtmosphereResponse.this);
                    } catch (IOException e) {
                        handleException(e);
                        throw e;
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void close() throws java.io.IOException {
                    if (!validFlushOrClose()
                        || asyncIOWriter instanceof KeepOpenStreamAware) return;

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    try {
                        asyncIOWriter.close(AtmosphereResponse.this);
                    } catch (IOException e) {
                        handleException(e);
                        throw e;
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }
            };
        } else {
            return _r().getOutputStream() != null ? _r().getOutputStream() : new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                }
            };
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
                .append("\r\n");

        b.append("Content-Type").append(":").append(headers.get("Content-Type") == null ? contentType : headers.get("Content-Type")).append("\r\n");
        if (contentLength != -1) {
            b.append("Content-Length").append(":").append(contentLength).append("\r\n");
        }

        for (String s : headers().keySet()) {
            if (!s.equalsIgnoreCase("Content-Type")) {
                b.append(s).append(":").append(headers.get(s)).append("\r\n");
            }
        }
        b.deleteCharAt(b.length() - 2);
        b.append("\r\n\r\n");
        return b.toString();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (forceAsyncIOWriter || !delegateToNativeResponse) {
            return new PrintWriter(getOutputStream()) {
                @Override
                public void write(char[] chars, int offset, int lenght) {
                    boolean b = forceAsyncIOWriter;
                    try {
                        validAsyncIOWriter();

                        // Prevent StackOverflow
                        writeStatusAndHeaders();
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(chars, offset, lenght));
                    } catch (IOException e) {
                        handleException(e);
                        throw new RuntimeException(e);
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void write(char[] chars) {
                    boolean b = forceAsyncIOWriter;
                    try {
                        validAsyncIOWriter();

                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(chars));
                    } catch (IOException e) {
                        handleException(e);
                        throw new RuntimeException(e);
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void write(String s, int offset, int lenght) {
                    boolean b = forceAsyncIOWriter;

                    try {
                        validAsyncIOWriter();

                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, new String(s.substring(offset, lenght)));
                    } catch (IOException e) {
                        handleException(e);
                        throw new RuntimeException(e);
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void write(String s) {

                    boolean b = forceAsyncIOWriter;
                    try {
                        validAsyncIOWriter();

                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        forceAsyncIOWriter = false;
                        asyncIOWriter.write(AtmosphereResponse.this, s);
                    } catch (IOException e) {
                        handleException(e);
                        throw new RuntimeException(e);
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void flush() {
                    if (!validFlushOrClose()) return;

                    boolean b = forceAsyncIOWriter;
                    try {
                        writeStatusAndHeaders();
                        // Prevent StackOverflow
                        forceAsyncIOWriter = false;
                        asyncIOWriter.flush(AtmosphereResponse.this);
                    } catch (IOException e) {
                        handleException(e);
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }

                @Override
                public void close() {
                    if (!validFlushOrClose()
                        || asyncIOWriter instanceof KeepOpenStreamAware) return;

                    // Prevent StackOverflow
                    boolean b = forceAsyncIOWriter;
                    forceAsyncIOWriter = false;
                    try {
                        asyncIOWriter.close(AtmosphereResponse.this);
                    } catch (IOException e) {
                        handleException(e);
                    } finally {
                        forceAsyncIOWriter = b;
                    }
                }
            };
        } else {
            return _r().getWriter() != null ? _r().getWriter() : new PrintWriter(new StringWriter());
        }
    }

    @Override
    public void setContentLength(int len) {
        headers.put("Content-Length", String.valueOf(len));

        if (!delegateToNativeResponse) {
            contentLength = len;
        } else {
            _r().setContentLength(len);
        }
    }

    @Override
    public void setContentType(String contentType) {
        headers.put("Content-Type", String.valueOf(contentType));

        if (!delegateToNativeResponse) {
            this.contentType = contentType;
        } else {
            _r().setContentType(contentType);
        }
    }

    @Override
    public String getContentType() {
        return getHeader("Content-type");
    }

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

    @Override
    public void setLocale(Locale locale) {
        if (!delegateToNativeResponse) {
            this.locale = locale;
        } else {
            _r().setLocale(locale);
        }
    }

    @Override
    public Locale getLocale() {
        if (!delegateToNativeResponse) {
            return locale;
        } else {
            return _r().getLocale();
        }
    }

    /**
     * Return the underlying {@link AsyncIOWriter}.
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
     * Return the associated {@link AtmosphereRequest}.
     *
     * @return the associated {@link AtmosphereRequest}
     */
    public AtmosphereRequest request() {
        return atmosphereRequest;
    }

    /**
     * Set the associated {@link AtmosphereRequest}.
     *
     * @param atmosphereRequest a {@link AtmosphereRequest}
     * @return this
     */
    public AtmosphereResponse request(AtmosphereRequest atmosphereRequest) {
        this.atmosphereRequest = atmosphereRequest;
        return this;
    }

    /**
     * Close the associated {@link AsyncIOWriter}.
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
        if (resource() != null && resource().transport() != AtmosphereResource.TRANSPORT.WEBSOCKET) {
            try {
                if (isUsingStream()) {
                    getOutputStream().close();
                } else {
                    getWriter().close();
                }
            } catch (Exception e) {
                //https://github.com/Atmosphere/atmosphere/issues/1643
                logger.trace("Unexpected exception", e);
            }
        }
    }

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}
     *
     * @param data the String to write
     */
    public AtmosphereResponse write(String data) {
        return write(data, false);
    }

    private void handleException(Exception ex) {
        AtmosphereResource r = resource();
        if (r != null) {
            AtmosphereResourceImpl.class.cast(r).notifyListeners(
                    new AtmosphereResourceEventImpl(AtmosphereResourceImpl.class.cast(r), true, false));
            // Don't take any risk and remove it.
            r.getAtmosphereConfig().resourcesFactory().remove(uuid);
        }
        logger.trace("{} unexpected I/O exception {}", uuid, ex);
    }

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}. If writeUsingOriginalResponse is
     * set to true, execute the write without invoking the defined {@link AsyncIOWriter}.
     *
     * @param data                       the String to write
     * @param writeUsingOriginalResponse if true, execute the write without invoking the {@link AsyncIOWriter}
     */
    public AtmosphereResponse write(String data, boolean writeUsingOriginalResponse) {

        if (Proxy.class.isAssignableFrom(response.getClass())) {
            writeUsingOriginalResponse = false;
        }

        try {
            if (isUsingStream()) {
                try {
                    OutputStream o = writeUsingOriginalResponse ? _r().getOutputStream() : getOutputStream();
                    o.write(data.getBytes(getCharacterEncoding()));
                } catch (java.lang.IllegalStateException ex) {
                    logger.trace("", ex);
                }
            } else {
                PrintWriter w = writeUsingOriginalResponse ? _r().getWriter() : getWriter();
                w.write(data);
            }
        } catch (Exception ex) {
            handleException(ex);
            throw new RuntimeException(ex);
        }
        return this;
    }

    private boolean isUsingStream() {
        if (atmosphereRequest != null) {
            Object s = atmosphereRequest.getAttribute(PROPERTY_USE_STREAM);
            if (s != null) {
                usingStream.set((Boolean) s);
            }
        }

        // Property always take first.
        if (resource() != null) {
            boolean force = resource().forceBinaryWrite();
            if (!usingStream.get() && force) {
                usingStream.set(force);
            }
        }
        return usingStream.get();
    }

    /**
     * Write the bytes by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}.
     *
     * @param data the bytes to write
     */
    public AtmosphereResponse write(byte[] data) {
        return write(data, false);
    }

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is based
     * on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}. If writeUsingOriginalResponse is set to
     * true, execute the write without invoking the defined {@link AsyncIOWriter}.
     *
     * @param data                       the bytes to write
     * @param writeUsingOriginalResponse if true, execute the write without invoking the {@link AsyncIOWriter}
     */
    public AtmosphereResponse write(byte[] data, boolean writeUsingOriginalResponse) {

        if (data == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }

        if (Proxy.class.isAssignableFrom(response.getClass())) {
            writeUsingOriginalResponse = false;
        }

        try {
            if (isUsingStream()) {
                try {
                    OutputStream o = writeUsingOriginalResponse ? _r().getOutputStream() : getOutputStream();
                    o.write(data);
                } catch (java.lang.IllegalStateException ex) {
                }
            } else {
                PrintWriter w = writeUsingOriginalResponse ? _r().getWriter() : getWriter();
                w.write(new String(data, getCharacterEncoding()));
            }
        } catch (Exception ex) {
            handleException(ex);
            throw new RuntimeException(ex);
        }
        return this;
    }

    /**
     * Write the bytes by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}.
     *
     * @param data   the bytes to write
     * @param offset the first byte position to write
     * @param length the data length
     */
    public AtmosphereResponse write(byte[] data, int offset, int length) {
        return write(data, offset, length, false);
    }

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is based
     * on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}. If writeUsingOriginalResponse is set to
     * true, execute the write without invoking the defined {@link AsyncIOWriter}.
     *
     * @param data                       the bytes to write
     * @param offset                     the first byte position to write
     * @param length                     the data length
     * @param writeUsingOriginalResponse if true, execute the write without invoking the {@link AsyncIOWriter}
     */
    public AtmosphereResponse write(byte[] data, int offset, int length, boolean writeUsingOriginalResponse) {

        if (data == null) {
            logger.error("Cannot write null value for {}", resource());
            return this;
        }

        if (Proxy.class.isAssignableFrom(response.getClass())) {
            writeUsingOriginalResponse = false;
        }

        try {
            if (isUsingStream()) {
                try {
                    OutputStream o = writeUsingOriginalResponse ? _r().getOutputStream() : getOutputStream();
                    o.write(data, offset, length);
                } catch (java.lang.IllegalStateException ex) {
                }
            } else {
                PrintWriter w = writeUsingOriginalResponse ? _r().getWriter() : getWriter();
                w.write(new String(data, offset, length, getCharacterEncoding()));
            }
        } catch (Exception ex) {
            handleException(ex);
            throw new RuntimeException(ex);
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

    @Override
    public void setResponse(ServletResponse response) {
        super.setResponse(response);
        if (HttpServletResponse.class.isAssignableFrom(response.getClass())) {
            this.response = HttpServletResponse.class.cast(response);
        }
    }

    /**
     * Create an instance not associated with any response parent.
     *
     * @return
     */
    public final static AtmosphereResponse newInstance() {
        return new Builder().build();
    }

    /**
     * Create a new instance to use with WebSocket.
     *
     * @return
     */
    public final static AtmosphereResponse newInstance(AtmosphereRequest request) {
        return new AtmosphereResponse(null, request, request.isDestroyable());
    }

    /**
     * Create a new instance to use with WebSocket.
     *
     * @return
     */
    public final static AtmosphereResponse newInstance(AtmosphereConfig config, AtmosphereRequest request, WebSocket webSocket) {
        boolean destroyable;
        String s = config.getInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
        if (s != null && Boolean.valueOf(s)) {
            destroyable = true;
        } else {
            destroyable = false;
        }
        return new AtmosphereResponse(webSocket, request, destroyable);
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

    /**
     * Return the {@link org.atmosphere.cpr.AtmosphereResource#uuid()} used by this object.
     *
     * @return the {@link org.atmosphere.cpr.AtmosphereResource#uuid()} used by this object.
     */
    public String uuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return "AtmosphereResponse{" +
                ", uuid=" + uuid +
                ", headers=" + headers +
                ", asyncIOWriter=" + asyncIOWriter +
                ", status=" + status +
                ", statusMessage='" + statusMessage + '\'' +
                ", atmosphereRequest=" + atmosphereRequest +
                ", writeStatusAndHeader=" + writeStatusAndHeader +
                ", delegateToNativeResponse=" + delegateToNativeResponse +
                ", destroyable=" + destroyable +
                ", response=" + response +
                '}';
    }
}
