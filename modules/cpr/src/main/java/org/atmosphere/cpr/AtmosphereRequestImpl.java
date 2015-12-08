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

import org.atmosphere.util.FakeHttpSession;
import org.atmosphere.util.QueryStringDecoder;
import org.atmosphere.util.ReaderInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE;

/**
 * An Atmosphere request representation. An {@link AtmosphereRequest} is a two-way communication channel between the
 * client and the server. If the {@link AtmosphereRequestImpl#isDestroyable()} is set to false, or if its
 * associated {@link AtmosphereResource} has been suspended, this object can be re-used at any moment between requests.
 * You can use its associated {@link AtmosphereResponse} to write bytes at any moment, making this object bi-directional.
 * <br/>
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereRequestImpl extends HttpServletRequestWrapper implements AtmosphereRequest {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereRequestImpl.class);
    private ServletInputStream bis;
    private BufferedReader br;
    private final Builder b;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private boolean queryComputed;
    private boolean cookieComputed;
    private volatile BufferedReader voidReader;
    private final ServletInputStream voidStream = new IS(new ByteArrayInputStream(new byte[0]));
    private AtomicBoolean streamSet = new AtomicBoolean();
    private AtomicBoolean readerSet = new AtomicBoolean();
    private String uuid;

    private AtmosphereRequestImpl(Builder b) {
        super(b.request == null ? new NoOpsRequest() : b.request);
        if (b.request == null) b.request(new NoOpsRequest());

        this.b = b;
        this.uuid = resource() != null ? resource().uuid() : "0";
    }

    private BufferedReader getVoidReader() {
        if (voidReader == null) {
            voidReader = new BufferedReader(new StringReader(""), 5);
        }
        return voidReader;
    }

    private ServletInputStream configureStream() {
        if (bis == null && !streamSet.getAndSet(true)) {
            if (b.inputStream != null) {
                bis = new IS(b.inputStream);
            } else if (b.reader == null) {
                if (b.body.dataBytes != null) {
                    bis = new ByteInputStream(b.body.dataBytes, b.body.offset, b.body.length);
                } else if (b.body.data != null) {
                    try {
                        byte[] bytes = b.body.data.getBytes("UTF-8");
                        bis = new ByteInputStream(bytes, 0, bytes.length);
                    } catch (UnsupportedEncodingException e) {
                        logger.trace("", e);
                    }
                }
            } else {
                bis = new IS(new ReaderInputStream(b.reader));
            }
        }
        return bis;
    }

    private BufferedReader configureReader() {
        if (br == null && !readerSet.getAndSet(false)) {
            if (b.reader != null) {
                br = new BufferedReader(b.reader);
            } else if (b.inputStream == null) {
                try {
                    if (b.body.dataBytes != null) {
                        br = new BufferedReader(new StringReader(new String(b.body.dataBytes, b.body.offset, b.body.length, b.encoding)));
                    } else if (b.body.data != null) {
                        br = new BufferedReader(new StringReader(b.body.data));
                    }
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            } else {
                br = new BufferedReader(new InputStreamReader(b.inputStream));
            }
        }
        return br;
    }

    @Override
    public boolean destroyed() {
        return destroyed.get();
    }

    @Override
    public AtmosphereRequest destroyable(boolean destroyable) {
        b.destroyable = destroyable;
        return this;
    }

    @Override
    public String getPathInfo() {
        return b.pathInfo != "" ? b.pathInfo : isNotNoOps() ? b.request.getPathInfo() : "";
    }

    @Override
    public String getPathTranslated() {
        return b.request.getPathTranslated();
    }

    @Override
    public String getQueryString() {
        return b.queryString != "" ? b.queryString : isNotNoOps() ? b.request.getQueryString() : toQs();
    }

    private String toQs() {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String[]> e : b.queryStrings.entrySet()) {
            for (String k : e.getValue()) {
                q.append(e.getKey()).append("=").append(k).append("&");
            }
        }
        if (q.length() > 0) q.deleteCharAt(q.length() - 1);
        return q.toString();
    }

    @Override
    public String getRemoteUser() {
        return b.principal != null ? b.principal.getName() : b.request.getRemoteUser();
    }

    @Override
    public String getRequestedSessionId() {
        return b.request.getRequestedSessionId();
    }

    @Override
    public String getMethod() {
        return b.methodType != null ? b.methodType : b.request.getMethod();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        return b.request.getPart(name);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return b.request.getParts();
    }

    @Override
    public String getContentType() {
        return b.contentType != null ? b.contentType : b.request.getContentType();
    }

    @Override
    public DispatcherType getDispatcherType() {
        return b.request.getDispatcherType();
    }

    @Override
    public String getServletPath() {
        return b.servletPath != "" ? b.servletPath : (isNotNoOps() ? b.request.getServletPath() : "");
    }

    @Override
    public String getRequestURI() {
        return b.requestURI != null ? b.requestURI : (isNotNoOps() ? b.request.getRequestURI() : "");
    }

    @Override
    public StringBuffer getRequestURL() {
        return b.requestURL != null ? new StringBuffer(b.requestURL) : (isNotNoOps() ? b.request.getRequestURL() : new StringBuffer());
    }

    @Override
    public Enumeration getHeaders(String name) {

        ArrayList list = new ArrayList<String>();
        // Never override the parent Request
        if (!name.equalsIgnoreCase("content-type")) {
            list = Collections.list(b.request.getHeaders(name));
        }

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
                if (list.isEmpty() && name.startsWith(X_ATMOSPHERE)) {
                    if (attributeWithoutException(b.request, name) != null) {
                        list.add(attributeWithoutException(b.request, name));
                    }
                }
            }
        }
        return Collections.enumeration(list);
    }

    @Override
    public int getIntHeader(String name) {
        return b.request.getIntHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set list = new HashSet();
        list.addAll(b.headers.keySet());

        list.addAll(Collections.list(b.request.getHeaderNames()));
        if (b.request != null) {
            Enumeration e = b.request.getAttributeNames();
            while (e.hasMoreElements()) {
                String name = e.nextElement().toString();
                if (name.startsWith(X_ATMOSPHERE)) {
                    list.add(name);
                }
            }
        }

        if (b.contentType != null) {
            list.add("Content-Type");
        }
        return Collections.enumeration(list);
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        return b.request.authenticate(response);
    }

    @Override
    public String getAuthType() {
        return b.authType != null ? b.authType : b.request.getAuthType();
    }

    @Override
    public String getContextPath() {
        String c = "/";
        try {
            c = isNotNoOps() && b.request.getContextPath() != null ? b.request.getContextPath() : b.contextPath != null ? b.contextPath : "";
        } catch (NullPointerException ex) {
            // https://github.com/Atmosphere/atmosphere/issues/1804
            logger.warn("Unexpected getContextPath exception. Forcing `/`", ex);
        }
        return c;
    }

    @Override
    public Cookie[] getCookies() {
        if (!cookieComputed) {
            cookieComputed = true;
            Cookie[] c = b.request.getCookies();
            if (c != null && c.length > 0) {
                b.cookies.addAll(Arrays.asList(c));
            }
        }
        return b.cookies.toArray(new Cookie[]{});
    }

    @Override
    public long getDateHeader(String name) {
        return b.request.getDateHeader(name);
    }

    @Override
    public String getHeader(String s) {
        return getHeader(s, true);
    }

    @Override
    public HttpServletRequest wrappedRequest() {
        return b.request;
    }

    @Override
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
                // Craziness with Struts 2 who wraps String attribute as BigDecimal
                // https://github.com/Atmosphere/atmosphere/issues/1367
                Object o = attributeWithoutException(b.request, s);
                if (o == null || String.class.isAssignableFrom(o.getClass())) {
                    name = String.class.cast(o);
                } else {
                    try {
                        if (HttpServletRequestWrapper.class.isAssignableFrom(b.request.getClass())) {
                            HttpServletRequest hsr = HttpServletRequestWrapper.class.cast(b.request);
                            while (hsr instanceof HttpServletRequestWrapper) {
                                hsr = (HttpServletRequest) ((HttpServletRequestWrapper) hsr).getRequest();
                                o = attributeWithoutException(hsr, s);
                                if (o == null || String.class.isAssignableFrom(o.getClass())) {
                                    name = String.class.cast(o);
                                    break;
                                }
                            }
                        }
                    } catch (Exception ex) {
                        logger.warn("", ex);
                    }
                }
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

    @Override
    public String getParameter(String s) {
        String name = isNotNoOps() ? b.request.getParameter(s) : null;
        if (name == null) {
            if (b.queryStrings.get(s) != null) {
                return b.queryStrings.get(s)[0];
            }
        }
        return name;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (!queryComputed) {
            queryComputed = true;
            Map<String, String[]> m = (isNotNoOps() ? b.request.getParameterMap() : Collections.<String, String[]>emptyMap());
            for (String e : m.keySet()) {
                b.queryStrings.put(e, getParameterValues(e));
            }
        }
        return Collections.unmodifiableMap(b.queryStrings);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

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

    @Override
    public String getProtocol() {
        return b.request.getProtocol();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (b.body.isEmpty()) {
            configureStream();
            return bis == null ? (isNotNoOps() ? b.request.getInputStream() : voidStream) : bis;
        } else if (b.body.hasString()) {
            bis = new IS(new ByteArrayInputStream(b.body.asString().getBytes()));
        } else if (b.body.hasBytes()) {
            bis = new IS(new ByteArrayInputStream(b.body.asBytes(), b.body.offset, b.body.byteLength()));
        }
        return bis;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (b.body.isEmpty()) {
            configureReader();
            return br == null ? (isNotNoOps() ? b.request.getReader() : getVoidReader()) : br;
        } else if (b.body.hasString()) {
            br = new BufferedReader(new StringReader(body().asString()));
        } else if (b.body.hasBytes()) {
            br = new BufferedReader(new StringReader(new String(body().asBytes(), body().byteOffset(), body().length)));
        }
        return br;
    }

    @Override
    public String getRealPath(String path) {
        return b.request.getRealPath(path);
    }

    @Override
    public AtmosphereRequest headers(Map<String, String> headers) {
        b.headers.putAll(headers);
        return this;
    }

    @Override
    public AtmosphereRequest header(String name, String value) {
        b.headers.put(name, value);
        return this;
    }

    @Override
    public AtmosphereRequest queryString(String qs) {

        if (qs == null) return this;

        if (!qs.isEmpty()) {
            QueryStringDecoder decoder = new QueryStringDecoder(getRequestURI() + "?" + qs);
            Map<String, List<String>> m = decoder.getParameters();
            Map<String, String[]> newM = new HashMap<String, String[]>();
            for (Map.Entry<String, List<String>> q : m.entrySet()) {
                newM.put(q.getKey(), q.getValue().toArray(new String[q.getValue().size()]));
            }
            b.queryStrings(newM);
        }
        b.queryString = qs;
        return this;
    }

    @Override
    public Map<String, String> headersMap() {
        return b.headers;
    }

    @Override
    public Map<String, String[]> queryStringsMap() {
        return b.queryStrings;
    }

    @Override
    public AtmosphereRequest method(String m) {
        b.method(m);
        return this;
    }

    @Override
    public AtmosphereRequest contentType(String m) {
        b.contentType(m);
        return this;
    }

    @Override
    public AtmosphereRequest body(String body) {
        b.body = new Body(body, null, 0, 0);
        return this;
    }

    @Override
    public AtmosphereRequest body(byte[] bytes) {
        b.body = new Body(null, bytes, 0, bytes.length);
        return this;
    }

    @Override
    public AtmosphereRequest body(InputStream body) {
        bis = new IS(body);
        br = new BufferedReader(new InputStreamReader(body));
        return this;
    }

    @Override
    public AtmosphereRequest body(Reader body) {
        bis = new IS(new ReaderInputStream(body));
        br = new BufferedReader(body);
        return this;
    }

    @Override
    public Body body() {
        return b.body;
    }

    @Override
    public AtmosphereRequest servletPath(String servletPath) {
        b.servletPath = servletPath;
        return this;
    }

    @Override
    public AtmosphereRequest contextPath(String contextPath) {
        b.contextPath = contextPath;
        return this;
    }

    @Override
    public AtmosphereRequest requestURI(String requestURI) {
        b.requestURI = requestURI;
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

    @Override
    public void setAttribute(String s, Object o) {
        if (o == null) {
            removeAttribute(s);
            return;
        }
        b.localAttributes.put(s, o);
        if (isNotNoOps()) {
            try {
                b.request.setAttribute(s, o);
            } catch (NullPointerException ex) {
                // https://github.com/Atmosphere/atmosphere/issues/1806
                logger.trace("", ex);
            }
        }
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        b.request.setCharacterEncoding(env);
    }

    @Override
    public AsyncContext startAsync() {
        return b.request.startAsync();
    }

    @Override
    public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
        return b.request.startAsync(request, response);
    }

    @Override
    public AsyncContext getAsyncContext() {
        return b.request.getAsyncContext();
    }

    @Override
    public Object getAttribute(String s) {
        return b.localAttributes.get(s) != null ? b.localAttributes.get(s) : (isNotNoOps() ? attributeWithoutException(b.request, s) : null);
    }

    @Override
    public void removeAttribute(String name) {
        b.localAttributes.remove(name);
        if (isNotNoOps()) {
            b.request.removeAttribute(name);
        }
    }

    @Override
    public LocalAttributes attributes() {
        return b.localAttributes;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (b.webSocketFakeSession != null) {
            return b.webSocketFakeSession;
        }

        if (resource() != null) {
            // UGLY, but we need to prevent looping here.
            HttpSession session = AtmosphereResourceImpl.class.cast(resource()).session;
            try {
                if (session != null) {
                    // check if session is valid (isNew() will throw if not)
                    session.isNew();
                    return session;
                }
            } catch (IllegalStateException e) {
                // session has been invalidated
            }
        }

        try {
            return b.request.getSession(create);
        } catch (java.lang.IllegalStateException ex) {
            // Jetty
            if (ex.getMessage() != null && ex.getMessage().equals("No SessionManager")) {
                return null;
            }
            throw ex;
        } catch (NullPointerException ex) {
            // GLASSFISH http://java.net/jira/browse/GLASSFISH-18856
            try {
                return b.request.getSession(create);
            } catch (Exception e) {
                logger.trace("", ex);
                return null;
            }
        } catch (RuntimeException ex) {
            // https://github.com/Atmosphere/atmosphere/issues/1974
            logger.trace("", ex);
            if (ex.getMessage() != null && ex.getMessage().contains("SESN0007E")) {
                return null;
            }
            throw ex;
        }
    }

    @Override
    public Principal getUserPrincipal() {
        return b.principal != null ? b.principal : b.request.getUserPrincipal();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return b.request.isRequestedSessionIdFromCookie();
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return b.request.isRequestedSessionIdFromUrl();
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return b.request.isRequestedSessionIdFromURL();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return b.request.isRequestedSessionIdValid();
    }

    @Override
    public boolean isUserInRole(String role) {
        return b.request.isUserInRole(role);
    }

    @Override
    public void login(String username, String password) throws ServletException {
        b.request.login(username, password);
    }

    @Override
    public void logout() throws ServletException {
        b.request.logout();
    }

    @Override
    public String getRemoteAddr() {
        return isNotNoOps() ? b.request.getRemoteAddr() : b.lazyRemote != null ? b.lazyRemote.getHostAddress() : b.remoteAddr;
    }

    @Override
    public String getRemoteHost() {
        return isNotNoOps() ? b.request.getRemoteHost() : b.lazyRemote != null ? b.lazyRemote.getHostName() : b.remoteHost;
    }

    @Override
    public int getRemotePort() {
        return isNotNoOps() ? b.request.getRemotePort() : b.lazyRemote != null ? b.lazyRemote.getPort() : b.remotePort;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return b.request.getRequestDispatcher(path);
    }

    @Override
    public String getScheme() {
        return b.request.getScheme();
    }

    @Override
    public String getServerName() {
        return b.serverName != "" ? b.serverName : b.request.getServerName();
    }

    @Override
    public int getServerPort() {
        return b.serverPort != 0 ? b.serverPort : b.request.getServerPort();
    }

    @Override
    public ServletContext getServletContext() {
        return b.request.getServletContext();
    }

    @Override
    public boolean isAsyncStarted() {
        return b.request.isAsyncStarted();
    }

    @Override
    public boolean isAsyncSupported() {
        try {
            return b.request.isAsyncSupported();
        } catch (Exception ex) {
            // Servlet 2.5 incompatible API.
            logger.trace("", ex);
            return false;
        }
    }

    @Override
    public boolean isSecure() {
        return isNotNoOps() ? b.request.isSecure() : b.isSecure;
    }

    @Override
    public String getLocalName() {
        return isNotNoOps() ? b.request.getLocalName() : b.lazyLocal != null ? b.lazyLocal.getHostName() : b.localName;
    }

    @Override
    public int getLocalPort() {
        return isNotNoOps() ? b.request.getLocalPort() : b.lazyLocal != null ? b.lazyLocal.getPort() : b.localPort;
    }

    @Override
    public String getLocalAddr() {
        return isNotNoOps() ? b.request.getLocalAddr() : b.lazyLocal != null ? b.lazyLocal.getHostAddress() : b.localAddr;
    }

    private boolean isNotNoOps() {
        return !NoOpsRequest.class.isAssignableFrom(b.request.getClass());
    }

    @Override
    public Locale getLocale() {
        return isNotNoOps() ? b.request.getLocale() : b.locales.iterator().hasNext() ? b.locales.iterator().next() : Locale.getDefault();
    }

    @Override
    public AtmosphereResource resource() {
        return (AtmosphereResource) getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return isNotNoOps() ? b.request.getLocales() : Collections.enumeration(b.locales);
    }

    @Override
    public boolean dispatchRequestAsynchronously() {
        return b.dispatchRequestAsynchronously;
    }

    @Override
    public boolean isDestroyable() {
        return b.destroyable;
    }

    @Override
    public AtmosphereRequest pathInfo(String pathInfo) {
        b.pathInfo = pathInfo;
        return this;
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        Set<String> l = new HashSet();
        l.addAll(b.localAttributes.unmodifiableMap().keySet());

        if (isNotNoOps()) {
            l.addAll(Collections.list(b.request.getAttributeNames()));
        }
        return Collections.enumeration(l);
    }

    private static Object attributeWithoutException(HttpServletRequest request, String attribute) {
        try {
            return request.getAttribute(attribute);
        } catch (NullPointerException ex) {
            // https://github.com/Atmosphere/atmosphere/issues/1732
            logger.trace("Unexpected NPE", ex);
            return "";
        }
    }

    @Override
    public LocalAttributes localAttributes() {
        return b.localAttributes;
    }

    @Override
    public String getCharacterEncoding() {
        return b.request.getCharacterEncoding() == null ? b.encoding : b.request.getCharacterEncoding();
    }

    @Override
    public int getContentLength() {
        if (b.contentLength == null) {
            return b.request.getContentLength();
        } else {
            return b.contentLength.intValue();
        }
    }

    @Override
    public String uuid() {
        if (uuid == "0") {
            this.uuid = resource() != null ? resource().uuid() : "0";
        }
        return uuid;
    }

    @Override
    public void destroy() {
        destroy(b.destroyable);
    }

    @Override
    public void destroy(boolean force) {
        if (!force) return;

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
    }

    @Override
    public void setRequest(ServletRequest request) {
        super.setRequest(request);
        if (HttpServletRequest.class.isAssignableFrom(request.getClass())) {
            b.request = HttpServletRequest.class.cast(request);
        }
    }

    public final static class Builder implements AtmosphereRequest.Builder {
        private final static Body NULL_BODY = new Body(null, null, 0, 0);
        private HttpServletRequest request;
        private String pathInfo = "";
        private String encoding = "UTF-8";
        private String methodType;
        private String contentType;
        private Long contentLength;
        private Map<String, String> headers = Collections.synchronizedMap(new HashMap<String, String>());
        private Map<String, String[]> queryStrings = Collections.synchronizedMap(new HashMap<String, String[]>());
        private String servletPath = "";
        private String requestURI;
        private String requestURL;
        private InputStream inputStream;
        private Reader reader;
        private String remoteAddr = "";
        private String remoteHost = "";
        private int remotePort;
        private String localAddr = "";
        private String localName = "";
        private int localPort;
        private boolean dispatchRequestAsynchronously;
        private boolean destroyable = true;
        private Set<Cookie> cookies = Collections.synchronizedSet(new HashSet<Cookie>());
        private final Set<Locale> locales = Collections.synchronizedSet(new HashSet<Locale>());
        private Principal principal;
        private String authType;
        private String contextPath = "";
        private String serverName = "";
        private int serverPort;
        private HttpSession webSocketFakeSession;
        private String queryString = "";
        private boolean isSecure;
        // Callable to lazily execute.
        private LazyComputation lazyRemote;
        private LazyComputation lazyLocal;
        public Body body;
        private LocalAttributes localAttributes = new LocalAttributes();

        public Builder() {
        }

        @Override
        public Builder destroyable(boolean destroyable) {
            this.destroyable = destroyable;
            return this;
        }

        @Override
        public Builder headers(Map<String, String> headers) {
            this.headers = Collections.synchronizedMap(headers);
            return this;
        }

        @Override
        public Builder cookies(Set<Cookie> cookies) {
            this.cookies = cookies;
            return this;
        }

        @Override
        public Builder dispatchRequestAsynchronously(boolean dispatchRequestAsynchronously) {
            this.dispatchRequestAsynchronously = dispatchRequestAsynchronously;
            return this;
        }

        @Override
        public Builder remoteAddr(String remoteAddr) {
            this.remoteAddr = remoteAddr;
            return this;
        }

        @Override
        public Builder remoteHost(String remoteHost) {
            this.remoteHost = remoteHost;
            return this;
        }

        @Override
        public Builder remotePort(int remotePort) {
            this.remotePort = remotePort;
            return this;
        }

        @Override
        public Builder localAddr(String localAddr) {
            this.localAddr = localAddr;
            return this;
        }

        @Override
        public Builder localName(String localName) {
            this.localName = localName;
            return this;
        }

        @Override
        public Builder localPort(int localPort) {
            this.localPort = localPort;
            return this;
        }

        @Override
        public Builder remoteInetSocketAddress(Callable remoteAddr) {
            this.lazyRemote = new LazyComputation(remoteAddr);
            return this;
        }

        @Override
        public Builder localInetSocketAddress(Callable localAddr) {
            this.lazyLocal = new LazyComputation(localAddr);
            return this;
        }

        @Override
        public Builder attributes(Map<String, Object> attributes) {
            localAttributes = new LocalAttributes(attributes);
            return this;
        }

        @Override
        public Builder request(HttpServletRequest request) {
            this.request = request;
            return this;
        }

        @Override
        public Builder servletPath(String servletPath) {
            this.servletPath = servletPath;
            return this;
        }

        @Override
        public Builder requestURI(String requestURI) {
            this.requestURI = requestURI;
            return this;
        }

        @Override
        public Builder requestURL(String requestURL) {
            this.requestURL = requestURL;
            return this;
        }

        @Override
        public Builder pathInfo(String pathInfo) {
            this.pathInfo = pathInfo;
            return this;
        }

        @Override
        public Builder queryString(String queryString) {
            this.queryString = queryString;
            return this;
        }

        @Override
        public Builder body(byte[] dataBytes) {
            return body(dataBytes, 0, dataBytes.length);
        }

        @Override
        public Builder body(byte[] dataBytes, int offset, int length) {
            this.body = new Body(null, dataBytes, offset, length);
            return this;
        }

        @Override
        public Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        @Override
        public Builder method(String methodType) {
            this.methodType = methodType;
            return this;
        }

        @Override
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        @Override
        public Builder contentLength(Long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        @Override
        public Builder body(String data) {
            this.body = new Body(data, null, 0, 0);
            return this;
        }

        @Override
        public Builder inputStream(InputStream inputStream) {
            this.inputStream = inputStream;
            return this;
        }

        @Override
        public Builder reader(Reader reader) {
            this.reader = reader;
            return this;
        }

        @Override
        public AtmosphereRequest build() {
            if (body == null) {
                body = NULL_BODY;
            }
            return new AtmosphereRequestImpl(this);
        }

        @Override
        public Builder queryStrings(Map<String, String[]> queryStrings) {
            this.queryStrings = Collections.synchronizedMap(queryStrings);
            return this;
        }

        @Override
        public Builder contextPath(String contextPath) {
            this.contextPath = contextPath == null ? "" : contextPath;
            return this;
        }

        @Override
        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        @Override
        public Builder serverPort(int serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        @Override
        public Builder session(HttpSession session) {
            if (request == null) {
                request = new NoOpsRequest();
            }

            if (NoOpsRequest.class.isAssignableFrom(request.getClass())) {
                NoOpsRequest.class.cast(request).fake = session;
            } else {
                webSocketFakeSession = session;
            }
            return this;
        }

        @Override
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        @Override
        public Builder authType(String authType) {
            this.authType = authType;
            return this;
        }

        @Override
        public Builder isSSecure(boolean isSecure) {
            this.isSecure = isSecure;
            return this;
        }

        @Override
        public Builder locale(Locale locale) {
            locales.add(locale);
            return this;
        }

        @Override
        public Builder userPrincipal(Principal userPrincipal) {
            this.principal = userPrincipal;
            return this;
        }
    }

    public final static class Body {
        private final String data;
        private final byte[] dataBytes;
        private final int offset;
        private final int length;
        private final boolean isEmpty;

        public Body(String data, byte[] dataBytes, int offset, int length) {
            this.data = data;
            this.dataBytes = dataBytes;
            this.offset = offset;
            this.length = length;
            isEmpty = data == null && dataBytes == null;
        }

        /**
         * True is the body is a String
         *
         * @return True is the body is a String
         */
        public boolean hasString() {
            return data != null;
        }

        /**
         * True is the body is a byte array
         *
         * @return True is the body is a byte array
         */
        public boolean hasBytes() {
            return dataBytes != null;
        }

        /**
         * Return the request body as a String. If the body was a byte array, this method will return null.
         *
         * @return the request body as a String. If the body was a byte array, this method will return null.
         */
        public String asString() {
            return data;
        }

        /**
         * Return the request body as a byte array. If the body was String, this method will return null.
         *
         * @return the request body as a byte array. If the body was String, this method will return null.
         */
        public byte[] asBytes() {
            return dataBytes;
        }

        /**
         * The {@link #asBytes()} offset
         *
         * @return The {@link #asBytes()} offset
         */
        public int byteOffset() {
            return offset;
        }

        /**
         * The {@link #asBytes()} length
         *
         * @return The {@link #asBytes()} length
         */
        public int byteLength() {
            return length;
        }

        /**
         * True if this object is empty
         *
         * @return True if this object is empty
         */
        public boolean isEmpty() {
            return isEmpty;
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

    /**
     * Create an instance of this class without an associated {@link HttpServletRequest}.
     *
     * @return an instance of this class without an associated {@link HttpServletRequest}
     */
    public final static AtmosphereRequest newInstance() {
        return new Builder().build();
    }

    /**
     * Wrap an {@link HttpServletRequest}.
     *
     * @param request {@link HttpServletRequest}
     * @return an {@link AtmosphereRequest}
     */
    public final static AtmosphereRequest wrap(HttpServletRequest request) {
        // Do not rewrap.
        if (AtmosphereRequestImpl.class.isAssignableFrom(request.getClass())) {
            return AtmosphereRequestImpl.class.cast(request);
        }

        Builder b = new Builder();
        Enumeration<String> e = request.getAttributeNames();
        String s;
        while (e.hasMoreElements()) {
            s = e.nextElement();
            b.localAttributes.put(s, attributeWithoutException(request, s));
        }
        return b.request(request).build();
    }


    /**
     * Copy the HttpServletRequest content inside an AtmosphereRequest. By default the returned AtmosphereRequest
     * is not destroyable.
     *
     * @param request {@link HttpServletRequest}
     * @return an {@link AtmosphereRequest}
     */
    public final static AtmosphereRequest cloneRequest(HttpServletRequest request, boolean loadInMemory, boolean copySession, boolean isDestroyable, boolean createSession) {
        Builder b;
        HttpServletRequest r;

        Cookie[] cs = request.getCookies();
        Set<Cookie> hs = Collections.synchronizedSet(new HashSet());
        if (cs != null) {
            for (Cookie c : cs) {
                hs.add(c);
            }
        }

        boolean isWrapped = false;
        if (AtmosphereRequestImpl.class.isAssignableFrom(request.getClass())) {
            b = AtmosphereRequestImpl.class.cast(request).b;
            isWrapped = true;
        } else {
            b = new Builder();
            b.request(request);
        }

        HttpSession session = null;
        if (copySession) {
            session = request.getSession(createSession);
            if (session != null) {
                session = new FakeHttpSession(session);
            } else {
                session = new FakeHttpSession("", null, System.currentTimeMillis(), -1);
            }
        }

        b.servletPath(request.getServletPath())
                .pathInfo(request.getPathInfo())
                .contextPath(request.getContextPath())
                .requestURI(request.getRequestURI())
                .requestURL(request.getRequestURL().toString())
                .method(request.getMethod())
                .serverName(request.getServerName())
                .serverPort(request.getServerPort())
                .remoteAddr(request.getRemoteAddr())
                .remoteHost(request.getRemoteHost())
                .remotePort(request.getRemotePort())
                .destroyable(isDestroyable)
                .cookies(hs)
                .session(session)
                .principal(request.getUserPrincipal())
                .authType(request.getAuthType())
                .isSSecure(request.isSecure());

        if (loadInMemory) {
            String s = (String) attributeWithoutException(request, FrameworkConfig.THROW_EXCEPTION_ON_CLONED_REQUEST);
            boolean throwException = s != null && Boolean.parseBoolean(s);
            r = new NoOpsRequest(throwException);
            if (isWrapped) {
                load(b.request, b);
            } else {
                load(request, b);
            }
            b.request(r);
        }

        return isWrapped ? AtmosphereRequestImpl.class.cast(request) : b.build();
    }

    public final static class NoOpsRequest implements HttpServletRequest {

        private boolean throwExceptionOnCloned;
        public HttpSession fake;
        private final static Enumeration<String> EMPTY_ENUM_STRING = Collections.enumeration(Collections.<String>emptyList());
        private final static Enumeration<Locale> EMPTY_ENUM_LOCALE = Collections.enumeration(Collections.<Locale>emptyList());
        private final static List<Part> EMPTY_ENUM_PART = Collections.<Part>emptyList();
        private final static Map<String, String[]> EMPTY_MAP_STRING = Collections.<String, String[]>emptyMap();
        private final static String[] EMPTY_ARRAY = new String[0];
        private final StringBuffer EMPTY_STRING_BUFFER = new StringBuffer();
        private final static Cookie[] EMPTY_COOKIE = new Cookie[0];
        private volatile BufferedReader voidReader;
        private final ServletInputStream voidStream = new AtmosphereRequestImpl.IS(new ByteArrayInputStream(new byte[0]));

        public NoOpsRequest() {
            this.throwExceptionOnCloned = false;
        }

        public NoOpsRequest(boolean throwExceptionOnCloned) {
            this.throwExceptionOnCloned = throwExceptionOnCloned;
        }

        private BufferedReader getVoidReader() {
            if (voidReader == null) {
                voidReader = new BufferedReader(new StringReader(""));
            }
            return voidReader;
        }

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
            return EMPTY_COOKIE;
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
            return EMPTY_ENUM_STRING;
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return EMPTY_ENUM_STRING;
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
            return EMPTY_ENUM_PART;
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
            return "";
        }

        @Override
        public String getRequestedSessionId() {
            return "";
        }

        @Override
        public String getRequestURI() {
            return "/";
        }

        @Override
        public StringBuffer getRequestURL() {
            return EMPTY_STRING_BUFFER;
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public HttpSession getSession() {
            return fake;
        }

        @Override
        public HttpSession getSession(boolean create) {
            if (create && fake == null) {
                fake = new FakeHttpSession("", null, System.currentTimeMillis(), -1) {
                    @Override
                    public void invalidate() {
                        fake = null;
                        super.invalidate();
                    }
                };
            }
            return fake;
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
            if (this.throwExceptionOnCloned == true) {
                throw new UnsupportedOperationException();
            }
            return false;
        }

        @Override
        public void login(String username, String password) throws ServletException {
            if (this.throwExceptionOnCloned == true) {
                throw new ServletException();
            }
        }

        @Override
        public void logout() throws ServletException {
            if (this.throwExceptionOnCloned == true) {
                throw new ServletException();
            }
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
            return EMPTY_ENUM_STRING;
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
            return "text/plain";
        }

        @Override
        public DispatcherType getDispatcherType() {
            return null;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return voidStream;
        }

        @Override
        public Locale getLocale() {
            return null;
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return EMPTY_ENUM_LOCALE;
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
            return EMPTY_MAP_STRING;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return EMPTY_ENUM_STRING;
        }

        @Override
        public String[] getParameterValues(String name) {
            return EMPTY_ARRAY;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return getVoidReader();
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
            return "ws";
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
            return true;
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

    public static final class LazyComputation {

        private final Callable<InetSocketAddress> callable;
        private InetSocketAddress address;

        public LazyComputation(Callable<InetSocketAddress> callable) {
            this.callable = callable;
        }

        public InetSocketAddress address() {
            if (address == null) {
                try {
                    address = callable.call();
                } catch (Exception e) {
                    logger.warn("", e);
                }

                // Falback
                if (address == null) {
                    address = new InetSocketAddress(8080);
                }
            }
            return address;
        }

        public int getPort() {
            return address().getPort();
        }

        public String getHostAddress() {
            return address().getAddress().getHostAddress();
        }

        public String getHostName() {
            return address().getHostName();
        }

    }

    private static void load(HttpServletRequest request, Builder b) {
        Enumeration<String> e = request.getHeaderNames();
        String s;
        while (e.hasMoreElements()) {
            s = e.nextElement();
            b.headers.put(s, request.getHeader(s));
        }

        e = request.getAttributeNames();
        while (e.hasMoreElements()) {
            s = e.nextElement();
            b.localAttributes.put(s, attributeWithoutException(request, s));
        }

        e = request.getParameterNames();
        while (e.hasMoreElements()) {
            s = e.nextElement();
            b.queryStrings.put(s, request.getParameterValues(s));
        }
        b.queryString = request.getQueryString();

        Enumeration<Locale> l = request.getLocales();
        while (l.hasMoreElements()) {
            b.locale(l.nextElement());
        }
    }

    @Override
    public String toString() {
        try {
            return "AtmosphereRequest{" +
                    " method=" + getMethod() +
                    " contextPath=" + getContextPath() +
                    " servletPath=" + getServletPath() +
                    " pathInfo=" + getPathInfo() +
                    " requestURI=" + getRequestURI() +
                    " requestURL=" + getRequestURL() +
                    " AtmosphereResource UUID=" + (resource() != null ? resource().uuid() : "") +
                    " destroyable=" + b.destroyable +
                    '}';
        } catch (Exception e) {
            // Jetty error
            return "AtmosphereRequest{" +
                    " contextPath=" + getContextPath() +
                    " servletPath=" + getServletPath() +
                    " pathInfo=" + getPathInfo() +
                    " requestURI=" + getRequestURI() +
                    " destroyable=" + b.destroyable +
                    '}';

        }
    }

    @Override
    public String requestURL() {
        return b.requestURL;
    }
}
