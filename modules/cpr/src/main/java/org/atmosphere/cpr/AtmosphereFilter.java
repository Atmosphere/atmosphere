/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.container.BlockingIOCometSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

/**
 * Atmosphere has support for {@link Filter}s, delegating all work to {@link AtmosphereServlet}. This {@link Filter}
 * only works with Jetty and Grizzly/GlassFish. With other containers it will use its {@link BlockingIOCometSupport}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereFilter.class);

    private final AtmosphereServlet as;

    private final static String EXCLUDE_FILES = "^.*\\.(ico|ICO|jpg|JPG|gif|GIF|doc|DOC|pdf|PDF)$";

    private String excluded = EXCLUDE_FILES;

    public AtmosphereFilter() {
        as = new AtmosphereServlet(true);
    }

    /**
     * Initialize the {@link Filter}.
     *
     * @param filterConfig The {@link javax.servlet.FilterConfig}
     * @throws ServletException
     */
    public void init(final FilterConfig filterConfig) throws ServletException {
        logger.info("AtmosphereServlet running as a Filter");

        as.init(new ServletConfig() {

            @Override
            public String getServletName() {
                return filterConfig.getFilterName();
            }

            @Override
            public ServletContext getServletContext() {
                return filterConfig.getServletContext();
            }

            @Override
            public String getInitParameter(String name) {
                return filterConfig.getInitParameter(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return filterConfig.getInitParameterNames();
            }
        });

        String s = filterConfig.getInitParameter(ApplicationConfig.ATMOSPHERE_EXCLUDED_FILE);
        if (s != null) {
            excluded = s;
        }

    }

    /**
     * Normal doFilter invocation.
     *
     * @param request
     * @param response
     * @param chain
     * @throws IOException
     * @throws ServletException
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        AtmosphereRequest req = AtmosphereRequestImpl.wrap((HttpServletRequest) request);
        AtmosphereResponse res = AtmosphereResponseImpl.wrap((HttpServletResponse) response);
        Action a = null;

        if (req.getServletPath() == null
                || (as.framework().getServletContext().getResource(req.getServletPath()) == null
                && !req.getServletPath().matches(excluded))) {
            a = as.framework().doCometSupport(req, res);
        }

        if (a == null || a.type() != Action.TYPE.SUSPEND) {
            chain.doFilter(request, response);
        }
    }

    public void destroy() {
        as.destroy();
    }
}
