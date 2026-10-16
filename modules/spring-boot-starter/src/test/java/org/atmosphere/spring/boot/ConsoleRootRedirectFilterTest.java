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
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Console root redirect answers only a {@code GET /} the application left
 * unserved (its 404), and leaves every other answer and request alone.
 */
class ConsoleRootRedirectFilterTest {

    private final ConsoleRootRedirectFilter filter = new ConsoleRootRedirectFilter();

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static final FilterChain NOTHING_MAPPED =
            (req, res) -> ((HttpServletResponse) res).sendError(HttpServletResponse.SC_NOT_FOUND);

    @Test
    void unservedRootRedirectsToTheConsoleKeepingTheQuery() throws Exception {
        var req = request("GET", "/");
        req.setQueryString("token=abc");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, NOTHING_MAPPED);
        assertThat(res.getStatus()).isEqualTo(302);
        assertThat(res.getRedirectedUrl()).isEqualTo("/atmosphere/console/?token=abc");
    }

    @Test
    void rootTheApplicationServesIsLeftAlone() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/"), res, (req, r) -> r.getWriter().write("own page"));
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getRedirectedUrl()).isNull();
        assertThat(res.getContentAsString()).isEqualTo("own page");
    }

    @Test
    void otherErrorsOnTheRootPassThrough() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/"), res,
                (req, r) -> ((HttpServletResponse) r).sendError(HttpServletResponse.SC_UNAUTHORIZED));
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getRedirectedUrl()).isNull();
    }

    @Test
    void notFoundOffTheRootIsNotRedirected() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/missing"), res, NOTHING_MAPPED);
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getRedirectedUrl()).isNull();
    }

    @Test
    void postToTheRootIsNotRedirected() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/"), res, NOTHING_MAPPED);
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getRedirectedUrl()).isNull();
    }
}
