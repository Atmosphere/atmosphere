/*
 * Copyright 2013 Jeanfrancois Arcand
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
/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2008 Sun Microsystems, Inc. All rights reserved.
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
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.util;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of <code>javax.servlet.FilterChain</code> used to manage
 * the execution of a set of filters for a particular request.  When the
 * set of defined filters has all been executed, the next call to
 * <code>doFilter()</code> will execute the servlet's <code>service()</code>
 * method itself.
 *
 * @author Craig R. McClanahan
 */
public final class AtmosphereFilterChain implements FilterChain {

    public static final int INCREMENT = 20;

    /**
     * Filters.
     */
    private FilterConfigImpl[] filters = new FilterConfigImpl[20];

    /**
     * The int which gives the current number of filters in the chain.
     */
    private int n = 0;
    /**
     * The servlet instance to be executed by this chain.
     */
    private Servlet servlet = null;
    private ServletConfig configImpl;

    public AtmosphereFilterChain() {
    }

    /**
     * Initialize the {@link Filter}
     *
     * @throws javax.servlet.ServletException
     */
    public void init() throws ServletException {
        for (FilterConfigImpl f : filters) {
            if (f != null) {
                f.getFilter().init(f);
            }
        }
        if (servlet != null) {
            servlet.init(configImpl);
        }
    }

    public void invokeFilterChain(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {
        request.setAttribute("pos", new AtomicInteger(0));
        doFilter(request, response);
    }

    /**
     * Invoke the next filter in this chain, passing the specified request
     * and response.  If there are no more filters in this chain, invoke
     * the <code>service()</code> method of the servlet itself.
     *
     * @param request  The servlet request we are processing
     * @param response The servlet response we are creating
     * @throws IOException      if an input/output error occurs
     * @throws ServletException if a servlet exception occurs
     */
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {

        // Call the next filter if there is one
        AtomicInteger pos = ((AtomicInteger) request.getAttribute("pos"));
        if (pos.get() < n) {
            FilterConfigImpl filterConfig = filters[pos.getAndIncrement()];
            Filter filter = null;
            try {
                filter = filterConfig.getFilter();
                filter.doFilter(request, response, this);
            } catch (IOException e) {
                throw e;
            } catch (ServletException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new ServletException("Throwable", e);
            }

            return;
        }

        try {
            if (servlet != null) {
                servlet.service(request, response);
            } else {
                RequestDispatcher rd = configImpl.getServletContext().getNamedDispatcher("default");
                if (rd == null) {
                    throw new ServletException("No Servlet Found");
                }
                rd.forward(request, response);
            }

        } catch (IOException e) {
            throw e;
        } catch (ServletException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new ServletException("Throwable", e);
        }

    }

    // -------------------------------------------------------- Package Methods

    /**
     * Add a filter to the set of filters that will be executed in this chain.
     *
     * @param filterConfig The FilterConfig for the servlet to be executed
     */
    public void addFilter(FilterConfigImpl filterConfig) {

        if (filterConfig.getFilter() == null) {
            throw new NullPointerException("Filter is null");
        }

        if (n == filters.length) {
            FilterConfigImpl[] newFilters =
                    new FilterConfigImpl[n + INCREMENT];
            System.arraycopy(filters, 0, newFilters, 0, n);
            filters = newFilters;
        }

        filters[n++] = filterConfig;
    }

    /**
     * Set the servlet that will be executed at the end of this chain.
     * Set by the mapper filter
     */
    public void setServlet(ServletConfig configImpl, Servlet servlet) {
        this.configImpl = configImpl;
        this.servlet = servlet;
    }

    public FilterConfigImpl getFilter(int i) {
        return filters[i];
    }

    public Servlet getServlet() {
        return servlet;
    }

    public ServletConfig getServletConfig() {
        return configImpl;
    }

    public void destroy() {
        if (n > 0 && filters != null) {
            for (int i = 0; i < filters.length; i++) {
                if (filters[i] != null) {
                    filters[i].recycle();
                }
            }
            filters = null;
        }

        if (servlet != null) {
            servlet.destroy();
        }
    }
}
