/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package org.atmosphere.cpr;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereServlet.Action;
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
 * Atmosphere supports for {@link Filter}, delegating all works to {@link AtmosphereServlet}.
 * This {@link Filter} only works with Jetty and Grizzly/GlassFish. With others
 * containers it will use its {@link BlockingIOCometSupport}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereFilter.class);

    private final AtmosphereServlet as;

    public AtmosphereFilter() {
        as = new AtmosphereServlet(true);
    }

    /**
     * Initialize the {@link Filter}
     *
     * @param filterConfig The {@link javax.servlet.FilterConfig}
     * @throws ServletException
     */
    public void init(final FilterConfig filterConfig) throws ServletException {
        logger.info("AtmosphereServlet running as a Filter");

        as.init(new ServletConfig() {

            public String getServletName() {
                return filterConfig.getFilterName();
            }

            public ServletContext getServletContext() {
                return filterConfig.getServletContext();
            }

            public String getInitParameter(String name) {
                return filterConfig.getInitParameter(name);
            }

            public Enumeration<String> getInitParameterNames() {
                return filterConfig.getInitParameterNames();
            }
        });
    }

    /**
     * Normal doFilter invokation.
     *
     * @param request
     * @param response
     * @param chain
     * @throws IOException
     * @throws ServletException
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Action a = as.doCometSupport((HttpServletRequest) request, (HttpServletResponse) response);
        if (a == null || a.type != Action.TYPE.SUSPEND) {
            chain.doFilter(request, response);
        }
    }

    public void destroy() {
        as.destroy();
    }
}
