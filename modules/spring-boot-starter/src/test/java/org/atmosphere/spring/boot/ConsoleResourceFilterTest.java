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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsoleResourceFilterTest {

    private AtmosphereAutoConfiguration.ConsoleResourceFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private ByteArrayOutputStream capturedOutput;

    @BeforeEach
    void setUp() throws IOException {
        filter = new AtmosphereAutoConfiguration.ConsoleResourceFilter("");
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        capturedOutput = new ByteArrayOutputStream();

        var servletOutputStream = new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                capturedOutput.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }
        };

        when(response.getOutputStream()).thenReturn(servletOutputStream);
    }

    @Test
    void servesIndexHtmlForConsoleRoot() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("text/html; charset=utf-8");
        assertThat(capturedOutput.toString()).contains("test console");
    }

    @Test
    void servesIndexHtmlForConsoleSlash() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("text/html; charset=utf-8");
        assertThat(capturedOutput.toString()).contains("test console");
    }

    @Test
    void guessesJsContentType() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/app.js");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("application/javascript; charset=utf-8");
    }

    @Test
    void guessesCssContentType() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/styles.css");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("text/css; charset=utf-8");
    }

    @Test
    void guessesSvgContentType() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/logo.svg");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("image/svg+xml");
    }

    @Test
    void guessesPngContentType() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/photo.png");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("image/png");
    }

    @Test
    void guessesIcoContentType() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/favicon.ico");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("image/x-icon");
    }

    @Test
    void fallsBackToOctetStream() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/data.xyz");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("application/octet-stream");
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/../../../etc/passwd");
        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void delegatesToChainForNonConsolePath() throws Exception {
        when(request.getRequestURI()).thenReturn("/other/path");
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void delegatesToChainWhenResourceMissing() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/nonexistent.html");
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ── F3: the console HTML carries the CSP as a response header (not a static
    //    <meta>), with frame-src widened to the MCP Apps sandbox origins. ──

    @Test
    void emitsCspHeaderOnHtmlWithDevSibling() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/");
        when(request.getScheme()).thenReturn("http");
        when(request.getHeader("Host")).thenReturn("localhost:8083");

        filter.doFilter(request, response, chain);

        var csp = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                csp.capture());
        assertThat(csp.getValue()).contains("frame-src 'self' http://127.0.0.1:8083");
        // Nonce-based strict CSP: per-request nonce + strict-dynamic, no 'unsafe-inline' anywhere.
        assertThat(csp.getValue()).contains("script-src 'nonce-");
        assertThat(csp.getValue()).contains("'strict-dynamic'");
        assertThat(csp.getValue()).doesNotContain("'unsafe-inline'");
        assertThat(csp.getValue()).contains("object-src 'none'");
    }

    @Test
    void doesNotEmitCspHeaderOnAssets() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/app.js");
        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(
                org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doesNotEmitCspHeaderOnSandboxHtml() throws Exception {
        // The MCP Apps sandbox proxy runs an inline bootstrap script and builds
        // its own inner CSP — a script-src 'self' header would break it.
        when(request.getRequestURI()).thenReturn("/atmosphere/console/sandbox.html");
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setContentType("text/html; charset=utf-8");
        verify(response, never()).setHeader(
                org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void buildConsoleCspAllowsTheLiveWebTransportOrigin() {
        // The HTTP/3 sidecar binds its own port — without this connect-src
        // entry the CSP blocks the console's WebTransport-first connect and
        // every WT sample silently degrades to the WS fallback.
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("http", "127.0.0.1:8080", "", "n0nce", "https://127.0.0.1:4443");
        assertThat(csp).contains("connect-src 'self' ws: wss: https://127.0.0.1:4443;");
    }

    @Test
    void buildConsoleCspOmitsWebTransportWhenSidecarIsDown() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("http", "127.0.0.1:8080", "", "n0nce", null);
        assertThat(csp).contains("connect-src 'self' ws: wss:;");
    }

    @Test
    void webTransportOriginUsesThePageHostname() {
        // The console derives the WT URL from location.hostname, so the CSP
        // entry must be built from the same host the page was addressed by.
        assertThat(AtmosphereAutoConfiguration.ConsoleResourceFilter
                .webTransportOrigin("localhost:8080", 4443)).isEqualTo("https://localhost:4443");
        assertThat(AtmosphereAutoConfiguration.ConsoleResourceFilter
                .webTransportOrigin("127.0.0.1:8080", 4447)).isEqualTo("https://127.0.0.1:4447");
        assertThat(AtmosphereAutoConfiguration.ConsoleResourceFilter
                .webTransportOrigin("localhost:8080", null)).isNull();
        assertThat(AtmosphereAutoConfiguration.ConsoleResourceFilter
                .webTransportOrigin(null, 4443)).isNull();
    }

    @Test
    void buildConsoleCspSwapsLocalhostToLoopback() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("http", "localhost:8083", "", "n0nce");
        assertThat(csp).contains("frame-src 'self' http://127.0.0.1:8083;");
    }

    @Test
    void buildConsoleCspSwapsLoopbackToLocalhost() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("http", "127.0.0.1:8083", "", "n0nce");
        assertThat(csp).contains("frame-src 'self' http://localhost:8083;");
    }

    @Test
    void buildConsoleCspAddsConfiguredOrigin() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("https", "console.example.com", "https://sandbox.example.com", "n0nce");
        // Non-loopback host → no dev sibling, but the configured origin is allowed.
        assertThat(csp).contains("frame-src 'self' https://sandbox.example.com;");
    }

    @Test
    void buildConsoleCspSkipsConfiguredOriginWhenSameAsCurrent() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("http", "localhost:8083", "http://localhost:8083", "n0nce");
        // Configured == current origin → only the dev sibling is added, not a self-frame dup.
        assertThat(csp).contains("frame-src 'self' http://127.0.0.1:8083;");
    }

    @Test
    void buildConsoleCspNonLoopbackHasNoSibling() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("https", "console.example.com", "", "n0nce");
        assertThat(csp).contains("frame-src 'self';");
    }

    @Test
    void siblingOriginReturnsNullForNonLoopback() {
        assertThat(AtmosphereAutoConfiguration.ConsoleResourceFilter
                .siblingOrigin("http", "example.com:9000")).isNull();
        assertThat(AtmosphereAutoConfiguration.ConsoleResourceFilter
                .siblingOrigin("http", null)).isNull();
    }

    // ── Clickjacking + response hardening on console responses. ──

    @Test
    void emitsClickjackingAndHardeningHeadersOnIndex() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/");
        when(request.getScheme()).thenReturn("http");
        when(request.getHeader("Host")).thenReturn("localhost:8083");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("X-Frame-Options", "DENY");
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response).setHeader("Referrer-Policy", "no-referrer");
        // index.html is served no-store so the per-request nonce is never reused.
        verify(response).setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        var csp = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                csp.capture());
        assertThat(csp.getValue()).contains("frame-ancestors 'none'");
        assertThat(csp.getValue()).contains("script-src 'nonce-");
    }

    @Test
    void injectedNonceInHtmlMatchesCspHeaderNonce() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/");
        when(request.getScheme()).thenReturn("http");
        when(request.getHeader("Host")).thenReturn("localhost:8083");

        filter.doFilter(request, response, chain);

        var csp = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                csp.capture());
        var m = java.util.regex.Pattern.compile("script-src 'nonce-([^']+)'").matcher(csp.getValue());
        assertThat(m.find()).isTrue();
        var nonce = m.group(1);
        var body = capturedOutput.toString();
        // The placeholder must be substituted, and the HTML nonce must byte-match
        // the header nonce — any drift blocks every script/style in the browser.
        assertThat(body).doesNotContain("__ATMO_CSP_NONCE__");
        assertThat(body).contains("nonce=\"" + nonce + "\"");
    }

    @Test
    void doesNotEmitXFrameOptionsOnSandboxHtml() throws Exception {
        // sandbox.html is deliberately framed from a distinct sibling origin for
        // MCP Apps isolation — X-Frame-Options: DENY would break it.
        when(request.getRequestURI()).thenReturn("/atmosphere/console/sandbox.html");
        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(
                org.mockito.ArgumentMatchers.eq("X-Frame-Options"),
                org.mockito.ArgumentMatchers.anyString());
        // The MIME-sniffing guard is still applied cross-origin.
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    void emitsNosniffButNoXFrameOptionsOnAssets() throws Exception {
        when(request.getRequestURI()).thenReturn("/atmosphere/console/app.js");
        filter.doFilter(request, response, chain);

        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response, never()).setHeader(
                org.mockito.ArgumentMatchers.eq("X-Frame-Options"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void buildConsoleCspIsNonceBasedStrict() {
        var csp = AtmosphereAutoConfiguration.ConsoleResourceFilter
                .buildConsoleCsp("http", "localhost:8083", "", "n0nce");
        assertThat(csp).contains("script-src 'nonce-n0nce' 'strict-dynamic'");
        assertThat(csp).contains("style-src 'self' 'nonce-n0nce'");
        assertThat(csp).contains("base-uri 'none'");
        assertThat(csp).contains("frame-ancestors 'none'");
        assertThat(csp).doesNotContain("'unsafe-inline'");
    }
}
