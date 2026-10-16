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

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Sends {@code GET /} to the Atmosphere Console ({@code /atmosphere/console/},
 * query string preserved) when the application itself serves nothing at
 * {@code /}, so an app without its own root page does not land on Spring's
 * Whitelabel 404.
 *
 * <p>The application answers first: the request runs down the chain, and only
 * a {@code 404} sent through {@code sendError} — what the dispatcher answers
 * when no controller, view controller, welcome page or static
 * {@code index.html} maps {@code /} — becomes the redirect. Any other answer,
 * a controller's or a static page's, goes out untouched.</p>
 */
final class ConsoleRootRedirectFilter implements Filter {

    static final String CONSOLE_PATH = "/atmosphere/console/";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest httpReq)
                || !(res instanceof HttpServletResponse httpRes)
                || !"GET".equalsIgnoreCase(httpReq.getMethod())
                || !"/".equals(httpReq.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        var probe = new NotFoundProbe(httpRes);
        chain.doFilter(req, probe);
        if (probe.notFound && !httpRes.isCommitted()) {
            // The query came off the request line, so it cannot carry raw CR/LF;
            // the container encodes the Location header. Preserving it keeps a
            // landing token (e.g. ?token=… for the admin write surface).
            var query = httpReq.getQueryString();
            httpRes.resetBuffer();
            httpRes.sendRedirect(CONSOLE_PATH
                    + (query != null && !query.isBlank() ? "?" + query : ""));
        }
    }

    /** Holds back a 404 sent through {@code sendError}; passes everything else. */
    private static final class NotFoundProbe extends HttpServletResponseWrapper {

        private boolean notFound;

        NotFoundProbe(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            if (sc == SC_NOT_FOUND) {
                notFound = true;
                return;
            }
            super.sendError(sc, msg);
        }

        @Override
        public void sendError(int sc) throws IOException {
            if (sc == SC_NOT_FOUND) {
                notFound = true;
                return;
            }
            super.sendError(sc);
        }
    }
}
