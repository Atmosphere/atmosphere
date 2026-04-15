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
package org.atmosphere.websocket;

import org.atmosphere.cpr.ApplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketHandshakeFilterTest {

    private WebSocketHandshakeFilter filter;
    private FilterChain chain;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new WebSocketHandshakeFilter();
        chain = mock(FilterChain.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    @Test
    void initWithNoBannedVersions() throws ServletException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION)).thenReturn(null);
        assertDoesNotThrow(() -> filter.init(filterConfig));
    }

    @Test
    void initWithBannedVersions() throws ServletException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION)).thenReturn("0,7,8");
        assertDoesNotThrow(() -> filter.init(filterConfig));
    }

    @Test
    void doFilterPassesNonWebSocketRequestThrough() throws IOException, ServletException {
        // Non-websocket request: no Connection header
        when(request.getHeaders("Connection")).thenReturn(Collections.emptyEnumeration());
        when(request.getHeaders("connection")).thenReturn(Collections.emptyEnumeration());
        when(request.getHeader("X-Atmosphere-WebSocket-Proxy")).thenReturn(null);
        when(request.getHeader("X-Atmosphere-Transport")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterWithBannedVersionSends501() throws IOException, ServletException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION)).thenReturn("0,7");
        filter.init(filterConfig);

        setupWebSocketRequest(request, 7);

        filter.doFilter(request, response, chain);

        verify(response).sendError(501, "Websocket protocol not supported");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doFilterWithNonBannedVersionPassesThrough() throws IOException, ServletException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION)).thenReturn("0,7");
        filter.init(filterConfig);

        setupWebSocketRequest(request, 13);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterWebSocketNoBannedListPassesThrough() throws IOException, ServletException {
        // init with no banned versions
        FilterConfig filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION)).thenReturn(null);
        filter.init(filterConfig);

        setupWebSocketRequest(request, 13);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void destroyDoesNotThrow() {
        assertDoesNotThrow(() -> filter.destroy());
    }

    @Test
    void doFilterBannedVersionUsesSecWebSocketDraftFallback() throws IOException, ServletException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION)).thenReturn("8");
        filter.init(filterConfig);

        // Set up as websocket but Sec-WebSocket-Version returns -1 (not present)
        when(request.getHeader("X-Atmosphere-Transport")).thenReturn(null);
        when(request.getHeader("X-Atmosphere-WebSocket-Proxy")).thenReturn(null);
        when(request.getHeaders("Connection")).thenReturn(
                Collections.enumeration(Collections.singletonList("Upgrade")));
        when(request.getIntHeader("Sec-WebSocket-Version")).thenReturn(-1);
        when(request.getIntHeader("Sec-WebSocket-Draft")).thenReturn(8);
        when(request.getHeader("Connection")).thenReturn("Upgrade");

        filter.doFilter(request, response, chain);

        verify(response).sendError(501, "Websocket protocol not supported");
        verify(chain, never()).doFilter(request, response);
    }

    private void setupWebSocketRequest(HttpServletRequest req, int version) {
        when(req.getHeader("X-Atmosphere-Transport")).thenReturn(null);
        when(req.getHeader("X-Atmosphere-WebSocket-Proxy")).thenReturn(null);
        when(req.getHeaders("Connection")).thenReturn(
                Collections.enumeration(Collections.singletonList("Upgrade")));
        when(req.getIntHeader("Sec-WebSocket-Version")).thenReturn(version);
        when(req.getHeader("Connection")).thenReturn("Upgrade");
    }
}
