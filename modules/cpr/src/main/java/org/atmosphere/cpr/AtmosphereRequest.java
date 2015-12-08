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
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An Atmosphere request representation. An {@link AtmosphereRequest} is a two-way communication channel between the
 * client and the server. If the {@link AtmosphereRequestImpl#isDestroyable()} is set to false, or if its
 * associated {@link AtmosphereResource} has been suspended, this object can be re-used at any moment between requests.
 * You can use its associated {@link AtmosphereResponse} to write bytes at any moment, making this object bi-directional.
 * <br/>
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereRequest extends HttpServletRequest {
    boolean destroyed();

    AtmosphereRequest destroyable(boolean destroyable);

    /**
     * {@inheritDoc}
     */
    @Override
    String getPathInfo();

    /**
     * {@inheritDoc}
     */
    @Override
    String getPathTranslated();

    /**
     * {@inheritDoc}
     */
    @Override
    String getQueryString();

    /**
     * {@inheritDoc}
     */
    @Override
    String getRemoteUser();

    /**
     * {@inheritDoc}
     */
    @Override
    String getRequestedSessionId();

    /**
     * {@inheritDoc}
     */
    @Override
    String getMethod();

    /**
     * {@inheritDoc}
     */
    @Override
    Part getPart(String name) throws IOException, ServletException;

    /**
     * {@inheritDoc}
     */
    @Override
    Collection<Part> getParts() throws IOException, ServletException;

    /**
     * {@inheritDoc}
     */
    @Override
    String getContentType();

    /**
     * {@inheritDoc}
     */
    @Override
    DispatcherType getDispatcherType();

    /**
     * {@inheritDoc}
     */
    @Override
    String getServletPath();

    /**
     * {@inheritDoc}
     */
    @Override
    String getRequestURI();

    /**
     * {@inheritDoc}
     */
    @Override
    StringBuffer getRequestURL();

    /**
     * {@inheritDoc}
     */
    @Override
    Enumeration getHeaders(String name);

    /**
     * {@inheritDoc}
     */
    @Override
    int getIntHeader(String name);

    /**
     * {@inheritDoc}
     */
    @Override
    Enumeration<String> getHeaderNames();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean authenticate(HttpServletResponse response) throws IOException, ServletException;

    /**
     * {@inheritDoc}
     */
    @Override
    String getAuthType();

    /**
     * {@inheritDoc}
     */
    @Override
    String getContextPath();

    /**
     * {@inheritDoc}
     */
    @Override
    Cookie[] getCookies();

    /**
     * {@inheritDoc}
     */
    @Override
    long getDateHeader(String name);

    /**
     * {@inheritDoc}
     */
    @Override
    String getHeader(String s);

    HttpServletRequest wrappedRequest();

    String getHeader(String s, boolean checkCase);

    /**
     * {@inheritDoc}
     */
    @Override
    String getParameter(String s);

    /**
     * {@inheritDoc}
     */
    @Override
    Map<String, String[]> getParameterMap();

    /**
     * {@inheritDoc}
     */
    @Override
    Enumeration<String> getParameterNames();

    /**
     * {@inheritDoc}
     */
    @Override
    String[] getParameterValues(String s);

    /**
     * {@inheritDoc}
     */
    @Override
    String getProtocol();

    /**
     * {@inheritDoc}
     */
    @Override
    ServletInputStream getInputStream() throws IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    BufferedReader getReader() throws IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    String getRealPath(String path);

    /**
     * Add all headers contained within the Map.
     *
     * @param headers
     * @return this;
     */
    AtmosphereRequest headers(Map<String, String> headers);

    /**
     * Add a header.
     *
     * @param name
     * @param value
     * @return this
     */
    AtmosphereRequest header(String name, String value);

    /**
     * Set the query string.
     *
     * @param qs
     * @return this
     */
    AtmosphereRequest queryString(String qs);

    Map<String, String> headersMap();

    Map<String, String[]> queryStringsMap();

    AtmosphereRequest method(String m);

    AtmosphereRequest contentType(String m);

    AtmosphereRequest body(String body);

    AtmosphereRequest body(byte[] bytes);

    AtmosphereRequest body(InputStream body);

    AtmosphereRequest body(Reader body);

    /**
     * Return the request's body. This method will return an empty Body if the underlying container or framework is using
     * InputStream or Reader.
     *
     * @return the request body;
     */
    AtmosphereRequestImpl.Body body();

    AtmosphereRequest servletPath(String servletPath);

    AtmosphereRequest contextPath(String contextPath);

    AtmosphereRequest requestURI(String requestURI);

    /**
     * {@inheritDoc}
     */
    @Override
    void setAttribute(String s, Object o);

    /**
     * {@inheritDoc}
     */
    @Override
    void setCharacterEncoding(String env) throws UnsupportedEncodingException;

    /**
     * {@inheritDoc}
     */
    @Override
    AsyncContext startAsync();

    /**
     * {@inheritDoc}
     */
    @Override
    AsyncContext startAsync(ServletRequest request, ServletResponse response);

    /**
     * {@inheritDoc}
     */
    @Override
    AsyncContext getAsyncContext();

    /**
     * {@inheritDoc}
     */
    @Override
    Object getAttribute(String s);

    /**
     * {@inheritDoc}
     */
    @Override
    void removeAttribute(String name);

    /**
     * Return the locally added attributes.
     *
     * @return the locally added attributes
     * @deprecated Use {@link #localAttributes()}
     */
    LocalAttributes attributes();

    /**
     * {@inheritDoc}
     */
    @Override
    HttpSession getSession();

    /**
     * {@inheritDoc}
     */
    @Override
    HttpSession getSession(boolean create);

    /**
     * {@inheritDoc}
     */
    @Override
    Principal getUserPrincipal();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isRequestedSessionIdFromCookie();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isRequestedSessionIdFromUrl();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isRequestedSessionIdFromURL();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isRequestedSessionIdValid();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isUserInRole(String role);

    /**
     * {@inheritDoc}
     */
    @Override
    void login(String username, String password) throws ServletException;

    /**
     * {@inheritDoc}
     */
    @Override
    void logout() throws ServletException;

    /**
     * {@inheritDoc}
     */
    @Override
    String getRemoteAddr();

    /**
     * {@inheritDoc}
     */
    @Override
    String getRemoteHost();

    /**
     * {@inheritDoc}
     */
    @Override
    int getRemotePort();

    /**
     * {@inheritDoc}
     */
    @Override
    RequestDispatcher getRequestDispatcher(String path);

    /**
     * {@inheritDoc}
     */
    @Override
    String getScheme();

    /**
     * {@inheritDoc}
     */
    @Override
    String getServerName();

    /**
     * {@inheritDoc}
     */
    @Override
    int getServerPort();

    /**
     * {@inheritDoc}
     */
    @Override
    ServletContext getServletContext();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isAsyncStarted();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isAsyncSupported();

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isSecure();

    /**
     * {@inheritDoc}
     */
    @Override
    String getLocalName();

    /**
     * {@inheritDoc}
     */
    @Override
    int getLocalPort();

    /**
     * {@inheritDoc}
     */
    @Override
    String getLocalAddr();

    /**
     * {@inheritDoc}
     */
    @Override
    Locale getLocale();

    /**
     * The {@link AtmosphereResource} associated with this request.
     *
     * @return an {@link AtmosphereResource}
     */
    AtmosphereResource resource();

    /**
     * {@inheritDoc}
     */
    @Override
    Enumeration<Locale> getLocales();

    /**
     * Dispatch the request asynchronously to container. The default is false.
     *
     * @return true to dispatch the request asynchronously to container.
     */
    boolean dispatchRequestAsynchronously();

    /**
     * Cjeck if this object can be destroyed. Default is true.
     */
    boolean isDestroyable();

    AtmosphereRequest pathInfo(String pathInfo);

    /**
     * {@inheritDoc}
     */
    @Override
    Enumeration<String> getAttributeNames();

    /**
     * Return a subset of the attributes set on this AtmosphereRequest, set locally by the framework or by an application. Attributes added using this method
     * won't be propagated to the original, container-only, native request object.
     *
     * @return a {@linkLocalAttributes>}
     */
    LocalAttributes localAttributes();

    /**
     * {@inheritDoc}
     */
    @Override
    String getCharacterEncoding();

    /**
     * {@inheritDoc}
     */
    @Override
    int getContentLength();

    /**
     * Return the underlying {@link AtmosphereResource#uuid()}. May return "0" if no {@link AtmosphereResource}
     * is associated with this object.
     *
     * @return the underlying {@link AtmosphereResource#uuid()}
     */
    String uuid();

    void destroy();

    void destroy(boolean force);

    /**
     * {@inheritDoc}
     */
    void setRequest(ServletRequest request);

    @Override
    String toString();

    String requestURL();


    final class LocalAttributes {

        private final Map<String, Object> localAttributes;

        public LocalAttributes(Map<String, Object> attributes) {
            this.localAttributes = attributes;
        }

        public LocalAttributes() {
            this.localAttributes = new ConcurrentHashMap<>();
        }

        public LocalAttributes put(String s, Object o) {
            localAttributes.put(s, o);
            return this;
        }

        public Object get(String s) {
            return localAttributes.get(s);
        }

        public Object remove(String name) {
            return localAttributes.remove(name);
        }

        public Map<String, Object> unmodifiableMap() {
            return Collections.unmodifiableMap(localAttributes);
        }

        public void clear() {
            synchronized (localAttributes) {
                localAttributes.clear();
            }
        }

        public boolean containsKey(String key) {
            return localAttributes.containsKey(key);
        }
    }
    
    interface Builder {
        Builder destroyable(boolean destroyable);

        Builder headers(Map<String, String> headers);

        Builder cookies(Set<Cookie> cookies);

        Builder dispatchRequestAsynchronously(boolean dispatchRequestAsynchronously);

        Builder remoteAddr(String remoteAddr);

        Builder remoteHost(String remoteHost);

        Builder remotePort(int remotePort);

        Builder localAddr(String localAddr);

        Builder localName(String localName);

        Builder localPort(int localPort);

        Builder remoteInetSocketAddress(Callable remoteAddr);

        Builder localInetSocketAddress(Callable localAddr);

        Builder attributes(Map<String, Object> attributes);

        Builder request(HttpServletRequest request);

        Builder servletPath(String servletPath);

        Builder requestURI(String requestURI);

        Builder requestURL(String requestURL);

        Builder pathInfo(String pathInfo);

        Builder queryString(String queryString);

        Builder body(byte[] dataBytes);

        Builder body(byte[] dataBytes, int offset, int length);

        Builder encoding(String encoding);

        Builder method(String methodType);

        Builder contentType(String contentType);

        Builder contentLength(Long contentLength);

        Builder body(String data);

        Builder inputStream(InputStream inputStream);

        Builder reader(Reader reader);

        AtmosphereRequest build();

        Builder queryStrings(Map<String, String[]> queryStrings);

        Builder contextPath(String contextPath);

        Builder serverName(String serverName);

        Builder serverPort(int serverPort);

        Builder session(HttpSession session);

        Builder principal(Principal principal);

        Builder authType(String authType);

        Builder isSSecure(boolean isSecure);

        Builder locale(Locale locale);

        Builder userPrincipal(Principal userPrincipal);
    }
}
