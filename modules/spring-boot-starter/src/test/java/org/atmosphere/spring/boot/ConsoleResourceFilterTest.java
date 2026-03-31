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
        filter = new AtmosphereAutoConfiguration.ConsoleResourceFilter();
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
}
