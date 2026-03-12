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

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import org.atmosphere.util.FakeHttpSession;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A no-op {@link HttpServletRequest} implementation used as a default when no real request is available.
 * <p>
 * This class was originally an inner class of {@link AtmosphereRequestImpl}.
 *
 * @author Jeanfrancois Arcand
 */
public class NoOpsRequest implements HttpServletRequest {

    private final boolean throwExceptionOnCloned;
    public HttpSession fake;
    private static final Enumeration<String> EMPTY_ENUM_STRING = Collections.enumeration(List.of());
    private static final Enumeration<Locale> EMPTY_ENUM_LOCALE = Collections.enumeration(List.of());
    private static final List<Part> EMPTY_ENUM_PART = List.of();
    private static final Map<String, String[]> EMPTY_MAP_STRING = Map.of();
    private static final String[] EMPTY_ARRAY = new String[0];
    private final StringBuffer EMPTY_STRING_BUFFER = new StringBuffer();
    private static final Cookie[] EMPTY_COOKIE = new Cookie[0];
    private volatile BufferedReader voidReader;
    private final ServletInputStream voidStream = new InputStreamServletAdapter(new ByteArrayInputStream(new byte[0]));

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
    public boolean authenticate(HttpServletResponse response) {
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
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
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
    public String changeSessionId() {
        return getSession(false).getId();
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

    @SuppressWarnings("deprecation")
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
        if (this.throwExceptionOnCloned) {
            throw new UnsupportedOperationException();
        }
        return false;
    }

    @Override
    public void login(String username, String password) throws ServletException {
        if (this.throwExceptionOnCloned) {
            throw new ServletException();
        }
    }

    @Override
    public void logout() throws ServletException {
        if (this.throwExceptionOnCloned) {
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
    public long getContentLengthLong() {
        return 0;
    }

    @Override
    public String getContentType() {
        return "text/plain";
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
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

    @SuppressWarnings("deprecation")
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
