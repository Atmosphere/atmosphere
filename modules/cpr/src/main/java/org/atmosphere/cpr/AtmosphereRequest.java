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

import org.atmosphere.util.FakeHttpSession;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE;

/**
 * An Atmosphere request representation. An {@link AtmosphereRequest} is a two-way communication channel between the
 * client and the server. If the {@link org.atmosphere.cpr.AtmosphereRequest#isDestroyable()} is set to false, or if its
 * associated {@link AtmosphereResource} has been suspended, this object can be re-used at any moments between requests.
 * You can use it's associated {@link AtmosphereResponse} to write bytes at any moment, making this object bi-directional.
 * <br/>
 * You can retrieve the AtmosphereResource can be retrieved as an attribute {@link FrameworkConfig#ATMOSPHERE_RESOURCE}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereRequest implements HttpServletRequest {

    private ServletInputStream bis;
    private BufferedReader br;
    private final String pathInfo;
    private final HttpSession session;
    private String methodType;
    private final Builder b;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private boolean queryComputed = false;

    private AtmosphereRequest(Builder b) {
        pathInfo = b.pathInfo == "" ? b.request.getPathInfo() : b.pathInfo;
        session = b.request == null ?
                new FakeHttpSession("", null, System.currentTimeMillis(), -1) : b.request.getSession();

        if (b.inputStream == null) {
            if (b.dataBytes != null) {
                configureStream(b.dataBytes, b.offset, b.length, b.encoding);
            } else if (b.data != null) {
                byte[] b2 = b.data.getBytes();
                configureStream(b2, 0, b2.length, "UTF-8");
            }
        } else {
            bis = new IS(b.inputStream);
            br = new BufferedReader(new InputStreamReader(b.inputStream));
        }

        methodType = b.methodType == null ? (isNotNoOps()? b.request.getMethod() : "GET") : b.methodType;

        if (b.request == null) b.request(new NoOpsRequest());

        this.b = b;
    }

    private void configureStream(byte[] bytes, int offset, int length, String encoding) {
        bis = new ByteInputStream(bytes, offset, length);
        try {
            br = new BufferedReader(new StringReader(new String(bytes, offset, length, encoding)));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean destroybed() {
        return destroyed.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPathInfo() {
        return pathInfo;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPathTranslated() {
        return b.request.getPathTranslated();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getQueryString() {
        return b.request.getQueryString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRemoteUser() {
        return b.request.getRemoteUser();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRequestedSessionId() {
        return b.request.getRequestedSessionId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMethod() {
        return methodType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Part getPart(String name) throws IOException, ServletException {
        return b.request.getPart(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return b.request.getParts();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getContentType() {
        return b.contentType != null ? b.contentType : b.request.getContentType();
    }

    @Override
    public DispatcherType getDispatcherType() {
        return b.request.getDispatcherType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getServletPath() {
        return b.servletPath != "" ? b.servletPath : (isNotNoOps()? b.request.getServletPath() : "");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRequestURI() {
        return b.requestURI != null ? b.requestURI : (isNotNoOps()? b.request.getRequestURI() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StringBuffer getRequestURL() {
        return b.requestURL != null ? new StringBuffer(b.requestURL) : (isNotNoOps()? b.request.getRequestURL() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration getHeaders(String name) {
        ArrayList list = Collections.list(b.request.getHeaders(name));
        if (name.equalsIgnoreCase("content-type")) {
            String s = getContentType();
            if (s != null) {
                list.add(s);
            }
        } else {
            if (b.headers.get(name) != null) {
                list.add(b.headers.get(name));
            }

            if (isNotNoOps()) {
                if (list.size() == 0 && name.startsWith(X_ATMOSPHERE)) {
                    if (b.request.getAttribute(name) != null) {
                        list.add(b.request.getAttribute(name));
                    }
                }
            }
        }
        return Collections.enumeration(list);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getIntHeader(String name) {
        return b.request.getIntHeader(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getHeaderNames() {
        ArrayList list = Collections.list(b.request.getHeaderNames());
        if (b.contentType != null) {
            list.add("Content-Type");
        }

        if (b.request != null) {
            Enumeration e = b.request.getAttributeNames();
            while (e.hasMoreElements()) {
                String name = e.nextElement().toString();
                if (name.startsWith(X_ATMOSPHERE)) {
                    list.add(name);
                }
            }
        }

        list.addAll(b.headers.keySet());

        return Collections.enumeration(list);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        return b.request.authenticate(response);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAuthType() {
        return b.request.getAuthType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getContextPath() {
        return b.request.getContextPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Cookie[] getCookies() {
        return b.request.getCookies();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getDateHeader(String name) {
        return b.request.getDateHeader(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHeader(String s) {
        return getHeader(s, true);
    }

    public String getHeader(String s, boolean checkCase) {

        if ("content-type".equalsIgnoreCase(s)) {
            return getContentType();
        }

        String name = b.request.getHeader(s);
        if (name == null) {
            if (b.headers.get(s) != null) {
                return b.headers.get(s);
            }

            if (s.startsWith(X_ATMOSPHERE) && isNotNoOps()) {
                name = (String) b.request.getAttribute(s);
            }
        }

        if (name == null && checkCase) {
            return getHeader(s.toLowerCase(), false);
        }

        if (name == null && "connection".equalsIgnoreCase(s)) {
            return "keep-alive";
        }

        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getParameter(String s) {
        String name = b.request == null ? null : b.request.getParameter(s);
        if (name == null) {
            if (b.queryStrings.get(s) != null) {
                return b.queryStrings.get(s)[0];
            }
        }
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String[]> getParameterMap() {
        if (!queryComputed) {
            queryComputed = true;
            Map<String, String[]> m = (isNotNoOps()? b.request.getParameterMap() : Collections.<String, String[]>emptyMap());
            for (String e : m.keySet()) {
                b.queryStrings.put(e, getParameterValues(e));
            }
        }
        return Collections.unmodifiableMap(b.queryStrings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] getParameterValues(String s) {
        String[] list = b.request.getParameterValues(s);
        if (list != null && b.queryStrings.get(s) != null) {
            String[] newList = b.queryStrings.get(s);
            if (!Arrays.deepEquals(list, newList)) {
                String[] s1 = new String[list.length + newList.length];
                System.arraycopy(list, 0, s1, 0, list.length);
                System.arraycopy(newList, 0, s1, list.length, newList.length);
                return s1;
            } else {
                return list;
            }
        } else {
            return list == null ? b.queryStrings.get(s) : list;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProtocol() {
        return b.request.getProtocol();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletInputStream getInputStream() throws IOException {
        return bis == null ? (isNotNoOps()? b.request.getInputStream() : null) : bis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BufferedReader getReader() throws IOException {
        return br == null ? (isNotNoOps()? b.request.getReader() : null) : br;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRealPath(String path) {
        return b.request.getRealPath(path);
    }

    /**
     * Add all headers contained with the Map.
     *
     * @param headers
     * @return this;
     */
    public AtmosphereRequest headers(Map<String, String> headers) {
        b.headers.putAll(headers);
        return this;
    }

    /**
     * Add a header.
     *
     * @param name
     * @param value
     * @return
     */
    public AtmosphereRequest header(String name, String value) {
        b.headers.put(name, value);
        return this;
    }

    public Map<String, String> headersMap() {
        return b.headers;
    }

    public AtmosphereRequest method(String m) {
        methodType = m;
        return this;
    }

    public AtmosphereRequest body(String body) {
        byte[] b = body.getBytes();
        configureStream(b, 0, b.length, "ISO-8859-1");
        return this;
    }

    private final static class ByteInputStream extends ServletInputStream {

        private final ByteArrayInputStream bis;

        public ByteInputStream(byte[] data, int offset, int length) {
            this.bis = new ByteArrayInputStream(data, offset, length);
        }

        @Override
        public int read() throws IOException {
            return bis.read();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute(String s, Object o) {
        b.localAttributes.put(s, o);
        if (isNotNoOps()) {
            b.request.setAttribute(s, o);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        b.request.setCharacterEncoding(env);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncContext startAsync() {
        return b.request.startAsync();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
        return b.request.startAsync(request, response);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncContext getAsyncContext() {
        return b.request.getAsyncContext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute(String s) {
        return b.localAttributes.get(s) != null ? b.localAttributes.get(s) : (isNotNoOps()? b.request.getAttribute(s) : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAttribute(String name) {
        b.localAttributes.remove(name);
        if (isNotNoOps()) {
            b.request.removeAttribute(name);
        }
    }

    /**
     * Return the locally added attribute.
     *
     * @return
     */
    public Map<String, Object> attributes() {
        return b.localAttributes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpSession getSession() {
        return session;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpSession getSession(boolean create) {
        return session;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Principal getUserPrincipal() {
        return b.request.getUserPrincipal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return b.request.isRequestedSessionIdFromCookie();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return b.request.isRequestedSessionIdFromUrl();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRequestedSessionIdFromURL() {
        return b.request.isRequestedSessionIdFromURL();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRequestedSessionIdValid() {
        return b.request.isRequestedSessionIdValid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUserInRole(String role) {
        return b.request.isUserInRole(role);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void login(String username, String password) throws ServletException {
        b.request.login(username, password);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logout() throws ServletException {
        b.request.logout();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRemoteAddr() {
        return isNotNoOps()? b.request.getRemoteAddr() : b.remoteAddr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRemoteHost() {
        return isNotNoOps()? b.request.getRemoteHost() : b.remoteHost;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRemotePort() {
        return isNotNoOps()? b.request.getRemotePort() : b.remotePort;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return b.request.getRequestDispatcher(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getScheme() {
        return b.request.getScheme();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getServerName() {
        return b.request.getServerName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getServerPort() {
        return b.request.getServerPort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletContext getServletContext() {
        return b.request.getServletContext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAsyncStarted() {
        return b.request.isAsyncStarted();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAsyncSupported() {
        return b.request.isAsyncSupported();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSecure() {
        return b.request.isSecure();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLocalName() {
        return isNotNoOps()? b.request.getLocalName() : b.localName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLocalPort() {
        return isNotNoOps()? b.request.getLocalPort() : b.localPort;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLocalAddr() {
        return isNotNoOps()? b.request.getLocalAddr() : b.localAddr;
    }

    private boolean isNotNoOps(){
        return !NoOpsRequest.class.isAssignableFrom(b.request.getClass());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Locale getLocale() {
        return b.request.getLocale();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<Locale> getLocales() {
        return b.request.getLocales();
    }

    /**
     * Dispatch the request asynchronously to container. The default is false.
     *
     * @return true to dispatch asynchronously the request to container.
     */
    public boolean dispatchRequestAsynchronously() {
        return b.dispatchRequestAsynchronously;
    }

    /**
     * Can this object be destroyed. Default is true.
     */
    public boolean isDestroyable() {
        return b.destroyable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getAttributeNames() {
        Set<String> l = new HashSet();
        l.addAll(b.localAttributes.keySet());
        Enumeration<String> e = (isNotNoOps()? b.request.getAttributeNames() : null);
        if (e != null) {
            while (e.hasMoreElements()) {
                l.add(e.nextElement());
            }
        }
        return Collections.enumeration(l);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCharacterEncoding() {
        return b.request.getCharacterEncoding();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getContentLength() {
        return b.request.getContentLength();
    }

    public void destroy() {
        if (!b.destroyable || destroyed.getAndSet(true)) return;

        b.localAttributes.clear();
        if (bis != null) {
            try {
                bis.close();
            } catch (IOException e) {
            }
        }

        if (br != null) {
            try {
                br.close();
            } catch (IOException e) {
            }
        }

        b.headers.clear();
        b.queryStrings.clear();

        // Help GC
        if (isNotNoOps()) {
            b.request.removeAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
        }
    }

    public final static class Builder {
        private HttpServletRequest request;
        private String pathInfo = "";
        private byte[] dataBytes;
        private int offset;
        private int length;
        private String encoding = "UTF-8";
        private String methodType;
        private String contentType;
        private String data;
        private Map<String, String> headers = new HashMap<String, String>();
        private Map<String, String[]> queryStrings = new HashMap<String, String[]>();
        private String servletPath = "";
        private String requestURI;
        private String requestURL;
        private Map<String, Object> localAttributes = new HashMap<String, Object>();
        private InputStream inputStream;
        private String remoteAddr = "";
        private String remoteHost = "";
        private int remotePort = 0;
        private String localAddr = "";
        private String localName = "";
        private int localPort = 0;
        private boolean dispatchRequestAsynchronously;
        private boolean destroyable = true;

        public Builder() {
        }

        public Builder destroyable(boolean destroyable) {
            this.destroyable = destroyable;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder dispatchRequestAsynchronously(boolean dispatchRequestAsynchronously) {
            this.dispatchRequestAsynchronously = dispatchRequestAsynchronously;
            return this;
        }

        public Builder remoteAddr(String remoteAddr) {
            this.remoteAddr = remoteAddr;
            return this;
        }

        public Builder remoteHost(String remoteHost) {
            this.remoteHost = remoteHost;
            return this;
        }

        public Builder remotePort(int remotePort) {
            this.remotePort = remotePort;
            return this;
        }

        public Builder localAddr(String localAddr) {
            this.localAddr = localAddr;
            return this;
        }

        public Builder localName(String localName) {
            this.localName = localName;
            return this;
        }

        public Builder localPort(int localPort) {
            this.localPort = localPort;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            localAttributes = attributes;
            return this;
        }

        public Builder request(HttpServletRequest request) {
            this.request = request;
            return this;
        }

        public Builder servletPath(String servletPath) {
            this.servletPath = servletPath;
            return this;
        }

        public Builder requestURI(String requestURI) {
            this.requestURI = requestURI;
            return this;
        }

        public Builder requestURL(String requestURL) {
            this.requestURL = requestURL;
            return this;
        }

        public Builder pathInfo(String pathInfo) {
            this.pathInfo = pathInfo;
            return this;
        }

        public Builder body(byte[] dataBytes, int offset, int length) {
            this.dataBytes = dataBytes;
            this.offset = offset;
            this.length = length;
            return this;
        }

        public Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder method(String methodType) {
            this.methodType = methodType;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder body(String data) {
            this.data = data;
            return this;
        }

        public Builder inputStream(InputStream inputStream) {
            this.inputStream = inputStream;
            return this;
        }

        public AtmosphereRequest build() {
            return new AtmosphereRequest(this);
        }

        public Builder queryStrings(Map<String, String[]> queryStrings) {
            this.queryStrings = queryStrings;
            return this;
        }
    }

    private final static class IS extends ServletInputStream {

        private final InputStream innerStream;

        public IS(InputStream innerStream) {
            super();
            this.innerStream = innerStream;
        }

        public int read() throws java.io.IOException {
            return innerStream.read();
        }

        public int read(byte[] bytes) throws java.io.IOException {
            return innerStream.read(bytes);
        }

        public int read(byte[] bytes, int i, int i1) throws java.io.IOException {
            return innerStream.read(bytes, i, i1);
        }


        public long skip(long l) throws java.io.IOException {
            return innerStream.skip(l);
        }

        public int available() throws java.io.IOException {
            return innerStream.available();
        }

        public void close() throws java.io.IOException {
            innerStream.close();
        }

        public synchronized void mark(int i) {
            innerStream.mark(i);
        }

        public synchronized void reset() throws java.io.IOException {
            innerStream.reset();
        }

        public boolean markSupported() {
            return innerStream.markSupported();
        }
    }

    private final static class NoOpsRequest implements HttpServletRequest {

        @Override
        public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
            return false;
        }

        @Override
        public String getAuthType() {
            return null;
        }

        @Override
        public String getContextPath() {
            return "";
        }

        @Override
        public Cookie[] getCookies() {
            return new Cookie[0];
        }

        @Override
        public long getDateHeader(String name) {
            return 0;
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.<String>emptyList());
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return Collections.enumeration(Collections.<String>emptyList());
        }

        @Override
        public int getIntHeader(String name) {
            return 0;
        }

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            return null;
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            return Collections.<Part>emptyList();
        }

        @Override
        public String getPathInfo() {
            return "";
        }

        @Override
        public String getPathTranslated() {
            return "";
        }

        @Override
        public String getQueryString() {
            return "";
        }

        @Override
        public String getRemoteUser() {
            return null;
        }

        @Override
        public String getRequestedSessionId() {
            return null;
        }

        @Override
        public String getRequestURI() {
            return "";
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer();
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public HttpSession getSession() {
            return null;
        }

        @Override
        public HttpSession getSession(boolean create) {
            return null;
        }

        @Override
        public Principal getUserPrincipal() {
            return null;
        }

        @Override
        public boolean isRequestedSessionIdFromCookie() {
            return false;
        }

        @Override
        public boolean isRequestedSessionIdFromUrl() {
            return false;
        }

        @Override
        public boolean isRequestedSessionIdFromURL() {
            return false;
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            return false;
        }

        @Override
        public boolean isUserInRole(String role) {
            return false;
        }

        @Override
        public void login(String username, String password) throws ServletException {

        }

        @Override
        public void logout() throws ServletException {

        }

        @Override
        public AsyncContext getAsyncContext() {
            return null;
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.enumeration(Collections.<String>emptyList());
        }

        @Override
        public String getCharacterEncoding() {
            return null;
        }

        @Override
        public int getContentLength() {
            return 0;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public DispatcherType getDispatcherType() {
            return null;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return null;
        }

        @Override
        public Locale getLocale() {
            return null;
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return Collections.enumeration(Collections.<Locale>emptyList());
        }

        @Override
        public String getLocalName() {
            return null;
        }

        @Override
        public int getLocalPort() {
            return 0;
        }

        @Override
        public String getLocalAddr() {
            return "";
        }

        @Override
        public String getParameter(String name) {
            return "";
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.<String, String[]>emptyMap();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(Collections.<String>emptyList());
        }

        @Override
        public String[] getParameterValues(String name) {
            return new String[0];
        }

        @Override
        public String getProtocol() {
            return "WebSocket";
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return null;
        }

        @Override
        public String getRealPath(String path) {
            return path;
        }

        @Override
        public String getRemoteAddr() {
            return "";
        }

        @Override
        public String getRemoteHost() {
            return "";
        }

        @Override
        public int getRemotePort() {
            return 0;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            return null;
        }

        @Override
        public String getScheme() {
            return "WebSocket";
        }

        @Override
        public String getServerName() {
            return "";
        }

        @Override
        public int getServerPort() {
            return 0;
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }

        @Override
        public boolean isAsyncStarted() {
            return false;
        }

        @Override
        public boolean isAsyncSupported() {
            return false;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public void removeAttribute(String name) {

        }

        @Override
        public void setAttribute(String name, Object o) {

        }

        @Override
        public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        }

        @Override
        public AsyncContext startAsync() {
            return null;
        }

        @Override
        public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
            return null;
        }
    }

    /**
     * Wrap an {@link HttpServletRequest}.
     *
     * @param request {@link HttpServletRequest}
     * @return an {@link AtmosphereRequest}
     */
    public final static AtmosphereRequest wrap(HttpServletRequest request) {
        return new Builder().request(request).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AtmosphereRequest that = (AtmosphereRequest) o;

        if (b != null ? !b.equals(that.b) : that.b != null) return false;
        if (bis != null ? !bis.equals(that.bis) : that.bis != null) return false;
        if (br != null ? !br.equals(that.br) : that.br != null) return false;
        if (methodType != null ? !methodType.equals(that.methodType) : that.methodType != null) return false;
        if (pathInfo != null ? !pathInfo.equals(that.pathInfo) : that.pathInfo != null) return false;
        if (session != null ? !session.equals(that.session) : that.session != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = bis != null ? bis.hashCode() : 0;
        result = 31 * result + (br != null ? br.hashCode() : 0);
        result = 31 * result + (pathInfo != null ? pathInfo.hashCode() : 0);
        result = 31 * result + (session != null ? session.hashCode() : 0);
        result = 31 * result + (methodType != null ? methodType.hashCode() : 0);
        result = 31 * result + (b != null ? b.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "AtmosphereRequest{" +
                "bis=" + bis +
                ", br=" + br +
                ", pathInfo='" + pathInfo + '\'' +
                ", session=" + session +
                ", methodType='" + methodType + '\'' +
                ", b=" + b +
                '}';
    }
}
