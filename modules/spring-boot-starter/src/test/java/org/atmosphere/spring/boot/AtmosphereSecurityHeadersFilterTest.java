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
package org.atmosphere.spring.boot;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

class AtmosphereSecurityHeadersFilterTest {

    private final AtmosphereAutoConfiguration.SecurityHeadersFilter filter =
            new AtmosphereAutoConfiguration.SecurityHeadersFilter("DENY");

    @Test
    void hardensTheApplicationsOwnRoot() throws Exception {
        var req = mock(HttpServletRequest.class);
        var res = mock(HttpServletResponse.class);
        var chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/");

        filter.doFilter(req, res, chain);

        verify(res).setHeader("X-Frame-Options", "DENY");
        verify(res).setHeader("X-Content-Type-Options", "nosniff");
        verify(res).setHeader("Referrer-Policy", "no-referrer");
        verify(res).setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        verify(chain).doFilter(req, res);
    }

    @Test
    void doesNotTouchAtmospherePaths() throws Exception {
        // /atmosphere/* (console nonce CSP, MCP, A2A, transports) must be left alone.
        var req = mock(HttpServletRequest.class);
        var res = mock(HttpServletResponse.class);
        var chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/atmosphere/console/");

        filter.doFilter(req, res, chain);

        verify(res, never()).setHeader(
                org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                org.mockito.ArgumentMatchers.anyString());
        verify(res, never()).setHeader(
                org.mockito.ArgumentMatchers.eq("X-Frame-Options"),
                org.mockito.ArgumentMatchers.anyString());
        verify(chain).doFilter(req, res);
    }

    @Test
    void frameAncestorsStayCoherentWithSameoriginFrameOptions() throws Exception {
        var sameOrigin = new AtmosphereAutoConfiguration.SecurityHeadersFilter("SAMEORIGIN");
        var req = mock(HttpServletRequest.class);
        var res = mock(HttpServletResponse.class);
        var chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/app.js");

        sameOrigin.doFilter(req, res, chain);

        verify(res).setHeader("X-Frame-Options", "SAMEORIGIN");
        verify(res).setHeader("Content-Security-Policy", "frame-ancestors 'self'");
    }
}
