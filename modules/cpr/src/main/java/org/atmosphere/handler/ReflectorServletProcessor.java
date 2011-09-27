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
 */
package org.atmosphere.handler;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServletProcessor;
import org.atmosphere.util.AtmosphereFilterChain;
import org.atmosphere.util.FilterConfigImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;

/**
 * Simple {@link AtmosphereServletProcessor} that delegate the {@link AtmosphereHandler#onRequest}
 * to a set of {@link FilterChain} and {@link Servlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
 * and store the {@link AtmosphereResource} as a {@link HttpServletRequest} attribute named
 * org.atmosphere.cpr.AtmosphereResource. The {@link AtmosphereResource} can later be retrieved
 * and used to supend/resume and broadcast
 *
 * @author Jeanfrancois Arcand
 */
public class ReflectorServletProcessor extends AbstractReflectorAtmosphereHandler
        implements AtmosphereServletProcessor {

    private final static String APPLICATION_NAME = "applicationClassName";
    private static final Logger logger = LoggerFactory.getLogger(ReflectorServletProcessor.class);

    private String servletClassName;
    private final ArrayList<String> filtersClass = new ArrayList<String>();
    private final FilterChainServletWrapper wrapper = new FilterChainServletWrapper();
    private final AtmosphereFilterChain filterChain = new AtmosphereFilterChain();
    private Servlet servlet;
    private String filterName;

    public ReflectorServletProcessor() {
    }

    void loadWebApplication(ServletConfig sc) throws MalformedURLException,
            InstantiationException, IllegalAccessException, ClassNotFoundException {

        URL url = sc.getServletContext().getResource("/WEB-INF/lib/");
        URLClassLoader urlC = new URLClassLoader(new URL[]{url},
                Thread.currentThread().getContextClassLoader());

        if (getServletClassName() != null && servlet == null) {
            try {
                servlet = (Servlet) urlC.loadClass(getServletClassName()).newInstance();
            } catch (NullPointerException ex) {
                // We failed to load the servlet, let's try directly.
                servlet = (Servlet) Thread.currentThread().getContextClassLoader()
                        .loadClass(servletClassName).newInstance();
            }
        }
        logger.info("Installing Servlet {}", servletClassName);
        filterChain.setServlet(sc, servlet);

        Filter f;
        for (String fClass : filtersClass) {
            try {
                f = (Filter) urlC.loadClass(fClass).newInstance();
            } catch (NullPointerException ex) {
                // We failed to load the Filter, let's try directly.
                f = (Filter) Thread.currentThread().getContextClassLoader()
                        .loadClass(fClass).newInstance();
            }
            FilterConfigImpl fc = new FilterConfigImpl(sc);
            fc.setFilter(f);

            if (filterName == null) {
                if (sc.getInitParameter(APPLICATION_NAME) != null) {
                    filterName = sc.getInitParameter(APPLICATION_NAME);
                } else {
                    filterName = f.getClass().getSimpleName();
                }
            }

            fc.setFilterName(filterName);
            filterChain.addFilter(fc);
            logger.info("Installing Filter {}", filterName);
        }

    }

    /**
     * Delegate the request to the Servlet.service method, and add the {@link AtmosphereResource}
     * to the {@link HttpServletRequest#setAttribute(java.lang.String, java.lang.Object))}.
     * The {@link AtmosphereResource} can ve retrieved using {@link AtmosphereServlet#ATMOSPHERE_RESOURCE}
     * value.
     *
     * @param r The {@link AtmosphereResource}
     * @throws java.io.IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> r)
            throws IOException {
        r.getRequest().setAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE, r);
        r.getRequest().setAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER, this);
        try {
            wrapper.service(r.getRequest(), r.getResponse());
        } catch (Throwable ex) {
            logger.error("onRequest()", ex);
            throw new RuntimeException(ex);
        }
    }

    public void init(ServletConfig sc) throws ServletException {
        try {
            loadWebApplication(sc);
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
        wrapper.init(sc);
    }

    public void destroy() {
        if (filterChain != null) {
            filterChain.destroy();
        }
    }

    /**
     * Set the Servlet class.
     *
     * @return the servletClass
     * @deprecated - use getServletClassName
     */
    public String getServletClass() {
        return servletClassName;
    }

    /**
     * Return the Servlet class name.
     *
     * @param servletClass the servletClass to set
     * @deprecated - use setServletClassName
     */
    public void setServletClass(String servletClass) {
        this.servletClassName = servletClass;
    }

    /**
     * Set the Servlet class.
     *
     * @return the servletClass
     */
    public String getServletClassName() {
        return servletClassName;
    }

    /**
     * Return the Servlet class name.
     *
     * @param servletClass the servletClass to set
     */
    public void setServletClassName(String servletClass) {
        this.servletClassName = servletClass;
    }

    /**
     * Add a FilterClass. Since we are using Reflection to call this method,
     * what we are really doing is addFilterClass.
     * <p/>
     * TODO: MUST ALLOW MORE THAN ONE FILTER
     *
     * @param filterClass
     */
    public void setFilterClassName(String filterClass) {
        if (filterClass == null) return;
        filtersClass.add(filterClass);
    }

    public Servlet getServlet() {
        return servlet;
    }

    public void setServlet(Servlet servlet) {
        this.servlet = servlet;
    }

    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }

    /**
     * Simple wrapper around a {@link Servlet}
     */
    private class FilterChainServletWrapper extends HttpServlet {

        @Override
        public void destroy() {
            filterChain.destroy();
        }

        @Override
        public String getInitParameter(String name) {
            return getServletConfig().getInitParameter(name);
        }

        @Override
        public Enumeration getInitParameterNames() {
            return getServletConfig().getInitParameterNames();
        }

        @Override
        public ServletConfig getServletConfig() {
            return filterChain.getServletConfig();
        }

        @Override
        public ServletContext getServletContext() {
            return getServletConfig().getServletContext();
        }

        @Override
        public String getServletInfo() {
            return filterChain.getServlet().getServletInfo();
        }

        @Override
        public void init(ServletConfig sc) throws ServletException {
            filterChain.init();
        }

        @Override
        public void init() throws ServletException {
        }

        @Override
        public void log(String msg) {
            getServletContext().log(getServletName() + ": " + msg);
        }

        @Override
        public void log(String message, Throwable t) {
            getServletContext().log(getServletName() + ": " + message, t);
        }

        @Override
        public void service(ServletRequest req, ServletResponse res)
                throws ServletException, IOException {
            filterChain.invokeFilterChain(req, res);
        }

        @Override
        public String getServletName() {
            return filterChain.getServletConfig().getServletName();
        }
    }
}
