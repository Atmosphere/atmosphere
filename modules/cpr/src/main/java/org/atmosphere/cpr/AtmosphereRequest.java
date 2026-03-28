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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.InputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Collections;
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

    HttpServletRequest wrappedRequest();

    String getHeader(String s, boolean checkCase);

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
     * The {@link AtmosphereResource} associated with this request.
     *
     * @return an {@link AtmosphereResource}
     */
    AtmosphereResource resource();

    /**
     * Dispatch the request asynchronously to container. The default is false.
     *
     * @return true to dispatch the request asynchronously to container.
     */
    boolean dispatchRequestAsynchronously();

    /**
     * Check if this object can be destroyed. Default is true.
     */
    boolean isDestroyable();

    AtmosphereRequest pathInfo(String pathInfo);

    /**
     * Return a subset of the attributes set on this AtmosphereRequest, set locally by the
     * framework or by an application. Attributes added using this method
     * won't be propagated to the original, container-only, native request object.
     *
     * @return a {@linkLocalAttributes>}
     */
    LocalAttributes localAttributes();

    /**
     * Return the underlying {@link AtmosphereResource#uuid()}. May return "0" if no {@link AtmosphereResource}
     * is associated with this object.
     *
     * @return the underlying {@link AtmosphereResource#uuid()}
     */
    String uuid();

    void destroy();

    void destroy(boolean force);

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
            if (o == null) {
                localAttributes.remove(s);
            } else {
                localAttributes.put(s, o);
            }
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
            localAttributes.clear();
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

        Builder remoteInetSocketAddress(Callable<InetSocketAddress> remoteAddr, boolean disableDnsLookup);

        Builder localInetSocketAddress(Callable<InetSocketAddress> localAddr, boolean disableDnsLookup);

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

        @Deprecated
        Builder isSSecure(boolean isSecure);

        Builder isSecure(boolean isSecure);

        Builder locale(Locale locale);

        Builder userPrincipal(Principal userPrincipal);
    }
}
