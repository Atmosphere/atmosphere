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
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.atmosphere.util.CookieUtil;
import org.atmosphere.util.ServletProxyFactory;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
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

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;

/**
 * An Atmosphere's response representation. An AtmosphereResponse can be used to construct a bi-directional asynchronous
 * application. If the underlying transport is a WebSocket or if its associated {@link AtmosphereResource} has been
 * suspended, this object can be used to write message back to the client at any moment.
 * <br/>
 * This object can delegate the write operation to {@link AsyncIOWriter}.
 */
public class AtmosphereResponseImpl extends HttpServletResponseWrapper implements AtmosphereResponse, CompletionAware {

    private final static boolean servlet30;

    static {
        Exception exception = null;
        try {
            Class.forName("jakarta.servlet.AsyncContext");
        } catch (Exception ex) {
            exception = ex;
        } finally {
            servlet30 = exception == null;
        }
    }

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResponseImpl.class);

    // -- Response metadata (state) --
    private final List<Cookie> cookies = new ArrayList<>();
    private final Map<String, String> headers;
    private int status = 200;
    private String statusMessage = "OK";
    private String charSet = "UTF-8";
    private long contentLength = -1;
    private String contentType = "text/html";
    private boolean isCommited;
    private Locale locale;
    private boolean headerHandled;
    private String uuid = "0";
    private boolean destroyable;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    // -- Request association --
    private AtmosphereRequest atmosphereRequest;

    // -- Native response delegate --
    @SuppressWarnings("rawtypes")
    private static final HttpServletResponse dsr = (HttpServletResponse)
            Proxy.newProxyInstance(AtmosphereResponseImpl.class.getClassLoader(), new Class[]{HttpServletResponse.class},
                    (proxy, method, args) -> ServletProxyFactory.getDefault().proxy(proxy, method, args));
    private HttpServletResponse response;

    // -- Writer/IO delegation (extracted to ResponseWriter) --
    private final ResponseWriter writer;

    public AtmosphereResponseImpl(AsyncIOWriter asyncIOWriter, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(dsr);
        response = dsr;
        this.atmosphereRequest = atmosphereRequest;
        this.headers = new HashMap<>();
        this.destroyable = destroyable;
        this.writer = new ResponseWriter(asyncIOWriter, false);
        this.writer.setContentTypeForSanitization(contentType);
    }

    public AtmosphereResponseImpl(HttpServletResponse r, AsyncIOWriter asyncIOWriter, AtmosphereRequest atmosphereRequest, boolean destroyable) {
        super(r);
        response = r;
        this.atmosphereRequest = atmosphereRequest;
        this.headers = new HashMap<>();
        this.destroyable = destroyable;
        this.writer = new ResponseWriter(asyncIOWriter, false);
        this.writer.setContentTypeForSanitization(contentType);
    }

    private AtmosphereResponseImpl(Builder b) {
        super(b.atmosphereResponse);

        response = b.atmosphereResponse;
        this.atmosphereRequest = b.atmosphereRequest;
        this.status = b.status;
        this.statusMessage = b.statusMessage;
        this.headers = b.headers;
        this.destroyable = b.destroyable;
        this.writer = new ResponseWriter(b.asyncIOWriter, b.writeStatusAndHeader.get());
        this.writer.setContentTypeForSanitization(contentType);
    }

    public final static class Builder implements AtmosphereResponse.Builder {
        private AsyncIOWriter asyncIOWriter;
        private int status = 200;
        private String statusMessage = "OK";
        private AtmosphereRequest atmosphereRequest;
        private HttpServletResponse atmosphereResponse = dsr;
        private final AtomicBoolean writeStatusAndHeader = new AtomicBoolean(true);
        private final Map<String, String> headers = new HashMap<>();
        private boolean destroyable = true;

        public Builder() {
        }

        @Override
        public Builder destroyable(boolean isRecyclable) {
            this.destroyable = isRecyclable;
            return this;
        }

        @Override
        public Builder asyncIOWriter(AsyncIOWriter asyncIOWriter) {
            this.asyncIOWriter = asyncIOWriter;
            return this;
        }

        @Override
        public Builder status(int status) {
            this.status = status;
            return this;
        }

        @Override
        public Builder statusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
            return this;
        }

        @Override
        public Builder request(AtmosphereRequest atmosphereRequest) {
            this.atmosphereRequest = atmosphereRequest;
            return this;
        }

        @Override
        public AtmosphereResponse build() {
            return new AtmosphereResponseImpl(this);
        }

        @Override
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        @Override
        public Builder writeHeader(boolean writeStatusAndHeader) {
            this.writeStatusAndHeader.set(writeStatusAndHeader);
            return this;
        }

        @Override
        public Builder response(HttpServletResponse res) {
            this.atmosphereResponse = res;
            return this;
        }
    }

    private HttpServletResponse _r() {
        return response;
    }

    /**
     * Package-private accessor for the content length value, used by {@link ResponseWriter}.
     */
    long contentLengthValue() {
        return contentLength;
    }

    @Override
    public void destroy() {
        destroy(destroyable);
    }

    @Override
    public void destroy(boolean force) {
        if (!force) return;
        logger.trace("{} destroyed", uuid);
        cookies.clear();
        headers.clear();
        atmosphereRequest = null;
        writer.destroy();
        destroyed.set(true);
    }

    @Override
    public boolean destroyed() {
        return destroyed.get();
    }

    @Override
    public void addCookie(Cookie cookie) {
        if (atmosphereRequest != null && atmosphereRequest.isSecure()) {
            cookie.setSecure(true);
        }
        if (writer.isDelegateToNativeResponse()) {
            _r().addCookie(cookie);
        } else {
            cookies.add(cookie);
        }
    }

    @Override
    public boolean containsHeader(String name) {
        return !writer.isDelegateToNativeResponse() ? (headers.get(name) != null) : _r().containsHeader(name);
    }

    @Override
    public String encodeURL(String url) {
        return response.encodeURL(url);
    }

    @Override
    public String encodeRedirectURL(String url) {
        return response.encodeRedirectURL(url);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String encodeUrl(String url) {
        return response.encodeURL(url);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String encodeRedirectUrl(String url) {
        return response.encodeRedirectURL(url);
    }

    @Override
    public AtmosphereResponse delegateToNativeResponse(boolean delegateToNativeResponse) {
        writer.setDelegateToNativeResponse(delegateToNativeResponse);
        return this;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        writer.sendError(this, _r(), sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
        writer.sendErrorNoMessage(this, _r(), sc);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        writer.sendRedirect(this, _r(), location);
    }

    @Override
    public void setDateHeader(String name, long date) {
        if (!writer.isDelegateToNativeResponse()) {
            headers.put(name, String.valueOf(date));
        } else {
            _r().setDateHeader(name, date);
        }
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (!writer.isDelegateToNativeResponse()) {
            headers.put(name, String.valueOf(date));
        } else {
            _r().setDateHeader(name, date);
        }
    }

    @Override
    public void setHeader(String name, String value) {
        if (value == null) headers.remove(name);
        else headers.put(name, value);

        if (writer.isDelegateToNativeResponse()) {
            _r().setHeader(name, value);
        }

        if (name.equalsIgnoreCase(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)) {
            uuid = value;
        }
    }

    @Override
    public void addHeader(String name, String value) {
        headers.put(name, value);

        if (writer.isDelegateToNativeResponse()) {
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
        this.status = status;
        if (writer.isDelegateToNativeResponse()) {
            _r().setStatus(status);
        }
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public ServletResponse getResponse() {
        if (response instanceof Proxy) {
            return this;
        } else {
            return super.getResponse();
        }
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
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

            if (s == null && servlet30) {
                s = _r().getHeader(name);
            }

            return s == null ? contentType : s;
        }

        s = headers.get(name);
        if (s == null && servlet30) {
            s = _r().getHeader(name);
        }

        return s;
    }

    @Override
    public Collection<String> getHeaders(String name) {
        var s = new ArrayList<String>();
        String h;
        if (name.equalsIgnoreCase("content-type")) {
            h = headers.get("Content-Type");
        } else {
            h = headers.get(name);
        }

        if (headers.containsKey(name)) {
            s.add(h);
        }

        if (servlet30) {
            Collection<String> r = _r().getHeaders(name);
            if (r != null && !r.isEmpty()) {
                s.addAll(r);
            }
        }

        if (!s.isEmpty()) {
            return Collections.unmodifiableList(s);
        }
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {

        Collection<String> r = null;

        if (servlet30) {
            r = _r().getHeaderNames();
        }

        Set<String> s = headers.keySet();
        if (r != null && !r.isEmpty()) {
            // detach the keyset from the original hashmap
            s = new HashSet<>(s);
            s.addAll(r);
        }

        return Collections.unmodifiableSet(s);
    }

    @Override
    public void setCharacterEncoding(String charSet) {
        if (!writer.isDelegateToNativeResponse()) {
            this.charSet = charSet;
        } else {
            _r().setCharacterEncoding(charSet);
        }
    }

    @Override
    public void flushBuffer() throws IOException {
        try {
            response.flushBuffer();
        } catch (NullPointerException ex) {
            //https://github.com/Atmosphere/atmosphere/issues/1943
            writer.handleException(this, ex);
        } catch (IOException ex) {
            writer.handleException(this, ex);
            throw ex;
        }
    }

    @Override
    public int getBufferSize() {
        return response.getBufferSize();
    }

    @Override
    public String getCharacterEncoding() {
        if (!writer.isDelegateToNativeResponse()) {
            return charSet;
        } else {
            return _r().getCharacterEncoding() == null ? charSet : _r().getCharacterEncoding();
        }
    }

    @Override
    public boolean isDestroyable() {
        return destroyable;
    }

    @Override
    public AtmosphereResponse destroyable(boolean destroyable) {
        this.destroyable = destroyable;
        return this;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return writer.createOutputStream(this, _r());
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return writer.createWriter(this, _r());
    }

    @Override
    public void setContentLength(int len) {
        headers.put("Content-Length", String.valueOf(len));

        if (!writer.isDelegateToNativeResponse()) {
            contentLength = len;
        } else {
            _r().setContentLength(len);
        }
    }

    @Override
    public void setContentType(String contentType) {
        headers.put("Content-Type", String.valueOf(contentType));

        if (!writer.isDelegateToNativeResponse()) {
            this.contentType = contentType;
        } else {
            _r().setContentType(contentType);
        }
        writer.setContentTypeForSanitization(contentType);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-type");
    }

    @Override
    public boolean isCommitted() {
        if (!writer.isDelegateToNativeResponse()) {
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
        if (!writer.isDelegateToNativeResponse()) {
            this.locale = locale;
        } else {
            _r().setLocale(locale);
        }
    }

    @Override
    public Locale getLocale() {
        if (!writer.isDelegateToNativeResponse()) {
            return locale;
        } else {
            return _r().getLocale();
        }
    }

    @Override
    public AsyncIOWriter getAsyncIOWriter() {
        return writer.getAsyncIOWriter();
    }

    @Override
    public AtmosphereResponse asyncIOWriter(AsyncIOWriter asyncIOWriter) {
        writer.setAsyncIOWriter(asyncIOWriter);
        return this;
    }

    @Override
    public AtmosphereRequest request() {
        return atmosphereRequest;
    }

    @Override
    public AtmosphereResponse request(AtmosphereRequest atmosphereRequest) {
        this.atmosphereRequest = atmosphereRequest;
        return this;
    }

    @Override
    public void close() throws IOException {
        writer.close(this);
    }

    @Override
    public void closeStreamOrWriter() {
        writer.closeStreamOrWriter(this);
    }

    @Override
    public AtmosphereResponse write(String data) {
        return write(data, false);
    }

    @Override
    public AtmosphereResponse write(String data, boolean writeUsingOriginalResponse) {
        writer.writeString(this, _r(), data, writeUsingOriginalResponse);
        return this;
    }

    @Override
    public AtmosphereResponse write(byte[] data) {
        return write(data, false);
    }

    @Override
    public AtmosphereResponse write(byte[] data, boolean writeUsingOriginalResponse) {
        writer.writeBytes(this, _r(), data, writeUsingOriginalResponse);
        return this;
    }

    @Override
    public AtmosphereResponse write(byte[] data, int offset, int length) {
        return write(data, offset, length, false);
    }

    @Override
    public AtmosphereResponse write(byte[] data, int offset, int length, boolean writeUsingOriginalResponse) {
        writer.writeBytes(this, _r(), data, offset, length, writeUsingOriginalResponse);
        return this;
    }

    @Override
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
        if (response instanceof HttpServletResponse httpServletResponse) {
            this.response = httpServletResponse;
        }
    }

    /**
     * Create an instance not associated with any response parent.
     *
     */
    public static AtmosphereResponse newInstance() {
        return new Builder().build();
    }

    /**
     * Create a new instance to use with WebSocket.
     *
     */
    public static AtmosphereResponse newInstance(AtmosphereRequest request) {
        return new AtmosphereResponseImpl(null, request, request.isDestroyable());
    }

    /**
     * Create a new instance to use with WebSocket.
     *
     */
    public static AtmosphereResponse newInstance(AtmosphereConfig config, AtmosphereRequest request, WebSocket webSocket) {
        boolean destroyable;
        String s = config.getInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
        destroyable = Boolean.parseBoolean(s);
        return new AtmosphereResponseImpl(webSocket, request, destroyable);
    }

    /**
     * Wrap an {@link HttpServletResponse}
     *
     * @param response {@link HttpServletResponse}
     * @return an {@link AtmosphereResponse}
     */
    public static AtmosphereResponse wrap(HttpServletResponse response) {
        return new Builder().response(response).build();
    }

    @Override
    public String uuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return "AtmosphereResponse{" +
                ", uuid=" + uuid +
                ", headers=" + headers +
                ", asyncIOWriter=" + writer.getAsyncIOWriter() +
                ", status=" + status +
                ", statusMessage='" + statusMessage + '\'' +
                ", atmosphereRequest=" + atmosphereRequest +
                ", writeStatusAndHeader=" + writer.getWriteStatusAndHeader() +
                ", delegateToNativeResponse=" + writer.isDelegateToNativeResponse() +
                ", destroyable=" + destroyable +
                ", response=" + response +
                '}';
    }

    @Override
    public void onComplete() {
        writer.onComplete(this);
    }

    @Override
    public boolean completed() {
        return writer.completed();
    }
}
