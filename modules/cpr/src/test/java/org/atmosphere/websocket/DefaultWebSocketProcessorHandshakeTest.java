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

import jakarta.servlet.http.HttpServletRequest;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Regression (registre#18): {@code WEBSOCKET_REQUIRE_SAME_ORIGIN} was
 * documented "Default: true" for years but never read — no code enforced
 * same-origin on WebSocket handshakes, leaving cross-site WebSocket
 * hijacking unmitigated while the docs promised otherwise. The default
 * processor now refuses cross-origin handshakes unless the flag is
 * explicitly disabled.
 */
class DefaultWebSocketProcessorHandshakeTest {

    private AtmosphereFramework framework;

    @AfterEach
    void tearDown() {
        if (framework != null) {
            framework.destroy();
        }
    }

    private DefaultWebSocketProcessor processor(String requireSameOrigin) throws Exception {
        framework = new AtmosphereFramework();
        if (requireSameOrigin != null) {
            framework.addInitParameter(
                    ApplicationConfig.WEBSOCKET_REQUIRE_SAME_ORIGIN, requireSameOrigin);
        }
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init();
        return (DefaultWebSocketProcessor)
                new DefaultWebSocketProcessor().configure(framework.getAtmosphereConfig());
    }

    private static HttpServletRequest request(String origin, String host) {
        var req = Mockito.mock(HttpServletRequest.class);
        when(req.getHeader("Origin")).thenReturn(origin);
        when(req.getHeader("Host")).thenReturn(host);
        when(req.getRequestURI()).thenReturn("/chat");
        when(req.isSecure()).thenReturn(false);
        return req;
    }

    @Test
    void crossOriginHandshakeIsRefusedByDefault() throws Exception {
        var p = processor(null);
        assertFalse(p.handshake(request("http://evil.example", "app.example.com")),
                "the documented default-on same-origin policy must actually refuse "
                + "a cross-origin handshake");
    }

    @Test
    void sameOriginHandshakeIsAcceptedByDefault() throws Exception {
        var p = processor(null);
        assertTrue(p.handshake(request("http://app.example.com", "app.example.com")));
    }

    @Test
    void missingOriginIsAcceptedForNonBrowserClients() throws Exception {
        var p = processor(null);
        assertTrue(p.handshake(request(null, "app.example.com")),
                "non-browser clients omit Origin and must not be locked out");
    }

    @Test
    void explicitOptOutDisablesTheCheck() throws Exception {
        var p = processor("false");
        assertTrue(p.handshake(request("http://evil.example", "app.example.com")),
                "cross-origin deployments opt out explicitly");
    }

    // ---- origin predicate ----

    @Test
    void originPredicateNormalizesDefaultPortsAndCase() {
        assertTrue(DefaultWebSocketProcessor.sameOrigin(
                "https://App.Example.com", "app.example.com", true));
        assertTrue(DefaultWebSocketProcessor.sameOrigin(
                "http://app.example.com:8080", "app.example.com:8080", false));
        assertFalse(DefaultWebSocketProcessor.sameOrigin(
                "http://app.example.com:8080", "app.example.com:9090", false));
        assertFalse(DefaultWebSocketProcessor.sameOrigin(
                "https://app.example.com", "app.example.com", false),
                "https origin implies 443; a plain-http host on 80 is a different origin");
    }

    @Test
    void unverifiableOriginsFailClosed() {
        assertFalse(DefaultWebSocketProcessor.sameOrigin("null", "app.example.com", false),
                "the literal 'null' origin (sandboxed iframe) must be refused");
        assertFalse(DefaultWebSocketProcessor.sameOrigin("::garbage::", "app.example.com", false));
        assertFalse(DefaultWebSocketProcessor.sameOrigin("http://a.example", null, false),
                "an Origin with no Host to compare against cannot be verified");
    }

    @Test
    void ipv6HostLiteralsCompare() {
        assertTrue(DefaultWebSocketProcessor.sameOrigin(
                "http://[::1]:8080", "[::1]:8080", false));
        assertFalse(DefaultWebSocketProcessor.sameOrigin(
                "http://[::1]:8080", "[::1]:9090", false));
    }
}
