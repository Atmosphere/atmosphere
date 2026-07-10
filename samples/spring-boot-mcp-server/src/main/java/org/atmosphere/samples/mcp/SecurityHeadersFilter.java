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
package org.atmosphere.samples.mcp;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies baseline security response headers to this sample's own React SPA
 * (served at {@code /} from {@code src/main/resources/static}) and its assets.
 *
 * <p>The framework's {@code ConsoleResourceFilter} already hardens
 * {@code /atmosphere/console/*} with a nonce-based strict CSP, so this filter
 * deliberately skips {@code /atmosphere/*} to avoid clobbering that policy.</p>
 *
 * <p>The CSP here is intentionally scoped to {@code frame-ancestors 'none'} — the
 * anti-clickjacking control (the confirmed gap), paired with {@code X-Frame-Options:
 * DENY} for legacy browsers. It deliberately does NOT restrict {@code connect-src}:
 * the Atmosphere client negotiates transports (WebSocket, SSE, and WebTransport/HTTP-3
 * on a separate Alt-Svc port), and a {@code connect-src 'self' ws: wss:} policy would
 * block the different-origin WebTransport endpoint and force a downgrade. Clickjacking
 * is the confirmed risk for a framable UI; a transport-breaking policy is not worth the
 * marginal XSS-backstop for a React shell that already loads only external scripts.</p>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = "frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var path = request.getRequestURI();
        // /atmosphere/* (console, MCP, A2A, transports) is hardened/handled by the
        // framework; only decorate this sample's own root SPA + static assets.
        if (path == null || !path.startsWith("/atmosphere/")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", CSP);
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
