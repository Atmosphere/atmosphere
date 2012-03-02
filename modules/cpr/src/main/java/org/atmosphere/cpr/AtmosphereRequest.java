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
import javax.servlet.http.HttpServletRequestWrapper;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE;

/**
 * A Builder for constructing {@link HttpServletRequest}
 */
public class AtmosphereRequest extends HttpServletRequestWrapper {

    private final ServletInputStream bis;
    private final BufferedReader br;
    private final Map<String, String> headers;
    private final Map<String, String[]> queryStrings;
    private final String pathInfo;
    private final HttpSession session;
    private final String methodType;
    private final String contentType;
    private final Builder b;

    private AtmosphereRequest(Builder b) {
        super(b.request);
        pathInfo = b.pathInfo == "" ? b.request.getPathInfo() : b.pathInfo;
        headers = b.headers == null ? new HashMap<String, String>() : b.headers;
        queryStrings = b.queryStrings == null ? new HashMap<String, String[]>() : b.queryStrings;
        session = b.request == null ? new FakeHttpSession("", null, System.currentTimeMillis()) : b.request.getSession();

        if (b.inputStream == null) {
            if (b.dataBytes != null) {
                bis = new ByteInputStream(b.dataBytes, b.offset, b.length);
                try {
                    br = new BufferedReader(new StringReader(new String(b.dataBytes, b.offset, b.length, b.encoding)));
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            } else if (b.data != null) {
                bis = new ByteInputStream(b.data.getBytes(), 0, b.data.getBytes().length);
                br = new BufferedReader(new StringReader(b.data));
            } else {
                bis = null;
                br = null;
            }
        } else {
            bis = new IS(b.inputStream);
            br = new BufferedReader(new InputStreamReader(b.inputStream));
        }
        methodType = b.methodType == null ? (b.request != null ? b.request.getMethod() : "GET") : b.methodType;
        contentType = b.contentType == null ? (b.request != null ? b.request.getContentType() : "text/plain") : b.contentType;
        this.b = b;
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
    public String getMethod() {
        return methodType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getContentType() {
        return contentType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getServletPath() {
        return b.servletPath != null ? b.servletPath : super.getServletPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRequestURI() {
        return b.requestURI != null ? b.requestURI : (b.request != null ? super.getRequestURI() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StringBuffer getRequestURL() {
        return b.requestURL != null ? new StringBuffer(b.requestURL) : (b.request != null ? b.request.getRequestURL() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration getHeaders(String name) {
        ArrayList list = Collections.list(super.getHeaders(name));
        if (name.equalsIgnoreCase("content-type")) {
            list.add(contentType);
        }

        if (headers.get(name) != null) {
            list.add(headers.get(name));
        }

        if (b.request != null) {
            if (list.size() == 0 && name.startsWith(X_ATMOSPHERE)) {
                if (b.request.getAttribute(name) != null) {
                    list.add(b.request.getAttribute(name));
                }
            }
        }
        return Collections.enumeration(list);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getHeaderNames() {
        ArrayList list = Collections.list(super.getHeaderNames());
        list.add("content-type");

        if (b.request != null) {
            Enumeration e = b.request.getAttributeNames();
            while (e.hasMoreElements()) {
                String name = e.nextElement().toString();
                if (name.startsWith(X_ATMOSPHERE)) {
                    list.add(name);
                }
            }
        }

        list.addAll(headers.keySet());

        return Collections.enumeration(list);
    }

    @Override
    public String getHeader(String s) {
        if (s.equalsIgnoreCase("Connection")) {
            return "keep-alive";
        } else if ("content-type".equalsIgnoreCase(s)) {
            return contentType;
        } else {
            String name = super.getHeader(s);
            if (name == null) {
                if (headers.get(s) != null) {
                    return headers.get(s);
                }

                if (s.startsWith(X_ATMOSPHERE) && b.request != null) {
                    return (String) b.request.getAttribute(s);
                }
            }
            return name;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getParameter(String s) {
        String name = super.getParameter(s);
        if (name == null) {
            if (queryStrings.get(s) != null) {
                return queryStrings.get(s)[0];
            }
        }
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> m = (b.request != null ? b.request.getParameterMap() : Collections.<String, String[]>emptyMap());
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            String[] s = queryStrings.get(e.getKey());
            if (s != null) {
                String[] s1 = new String[s.length + e.getValue().length];
                System.arraycopy(s, 0, s1, 0, s.length);
                System.arraycopy(s1, s.length + 1, e.getValue(), 0, e.getValue().length);
                queryStrings.put(e.getKey(), s1);
            } else {
                queryStrings.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(queryStrings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] getParameterValues(String s) {
        String[] list = super.getParameterValues(s) == null ? new String[0] : super.getParameterValues(s);
        if (queryStrings.get(s) != null) {
            String[] newList = queryStrings.get(s);
            String[] s1 = new String[list.length + newList.length];
            System.arraycopy(list, 0, s1, 0, list.length);
            System.arraycopy(s1, list.length, newList, 0, newList.length);
            list = s1;
        }
        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletInputStream getInputStream() throws IOException {
        return bis == null ? (b.request != null ? b.request.getInputStream() : null) : bis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BufferedReader getReader() throws IOException {
        return br == null ? (b.request != null ? b.request.getReader() : null) : br;
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
        if (b.request != null){
            b.request.setAttribute(s, o);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute(String s) {
        return b.localAttributes.get(s) != null ? b.localAttributes.get(s) : (b.request != null ? b.request.getAttribute(s) : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAttribute(String name) {
        b.localAttributes.remove(name);
        if (b.request != null) {
            b.request.removeAttribute(name);
        }
    }

    @Override
    public HttpSession getSession() {
        return session;
    }

    @Override
    public HttpSession getSession(boolean create) {
        return session;
    }

    @Override
    public String getRemoteAddr() {
        return b.remoteAddr;
    }

    @Override
    public String getRemoteHost() {
        return b.remoteHost;
    }

    @Override
    public int getRemotePort() {
        return b.remotePort;
    }

    @Override
    public String getLocalName() {
        return b.localName;
    }

    @Override
    public int getLocalPort() {
        return b.localPort;
    }

    @Override
    public String getLocalAddr() {
        return b.localAddr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getAttributeNames() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.putAll(b.localAttributes);

        Enumeration<String> e = (b.request != null ? b.request.getAttributeNames() : null);
        if (e != null) {
            String s;
            while (e.hasMoreElements()) {
                s = e.nextElement();
                m.put(s, b.request.getAttribute(s));
            }
        }
        return Collections.enumeration(m.keySet());
    }

    public void destroy() {
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

        headers.clear();
        queryStrings.clear();

        // Help GC
        if (b.request != null) {
            b.request.removeAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
            b.request = null;
        }
    }

    public final static class Builder {
        private HttpServletRequest request = new DummyHttpServletRequest();
        private String pathInfo = "";
        private byte[] dataBytes;
        private int offset;
        private int length;
        private String encoding = "UTF-8";
        private String methodType;
        private String contentType;
        private String data;
        private Map<String, String> headers;
        private Map<String, String[]> queryStrings;
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

        public Builder() {
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
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

    private final static class DummyHttpServletRequest implements HttpServletRequest {

        @Override
        public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getAuthType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getContextPath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Cookie[] getCookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDateHeader(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getHeader(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getIntHeader(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPathInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPathTranslated() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getQueryString() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRemoteUser() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRequestedSessionId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRequestURI() {
            throw new UnsupportedOperationException();
        }

        @Override
        public StringBuffer getRequestURL() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getServletPath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpSession getSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpSession getSession(boolean create) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Principal getUserPrincipal() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRequestedSessionIdFromCookie() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRequestedSessionIdFromUrl() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRequestedSessionIdFromURL() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUserInRole(String role) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void login(String username, String password) throws ServletException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void logout() throws ServletException {
            throw new UnsupportedOperationException();
        }

        @Override
        public AsyncContext getAsyncContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getCharacterEncoding() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getContentLength() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getContentType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DispatcherType getDispatcherType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Locale getLocale() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration<Locale> getLocales() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getLocalName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLocalPort() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getLocalAddr() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getParameter(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String[] getParameterValues(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getProtocol() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BufferedReader getReader() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRealPath(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRemoteAddr() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRemoteHost() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getRemotePort() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getScheme() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getServerName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getServerPort() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServletContext getServletContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAsyncStarted() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAsyncSupported() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSecure() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(String name, Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
            throw new UnsupportedOperationException();
        }

        @Override
        public AsyncContext startAsync() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
            throw new UnsupportedOperationException();
        }
    }

    public final static AtmosphereRequest wrap(HttpServletRequest request) {
        return new Builder().request(request).build();
    }
}
