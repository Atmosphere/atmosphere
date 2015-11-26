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

package org.atmosphere.handler;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServletProcessor;
import org.atmosphere.util.AtmosphereFilterChain;
import org.atmosphere.util.FilterConfigImpl;
import org.atmosphere.util.IOUtils;
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
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * An implementation of {@link AtmosphereHandler} using the {@link AtmosphereServletProcessor} that delegate the {@link AtmosphereHandler#onRequest}
 * to a set of {@link FilterChain} and {@link Servlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}
 * and store the {@link AtmosphereResource} as a {@link AtmosphereRequestImpl#getAttribute(String)} attribute named
 * {@link org.atmosphere.cpr.FrameworkConfig#ATMOSPHERE_RESOURCE}. The {@link AtmosphereResource} can later be retrieved
 * and used to suspend/resume and broadcast.
 *
 * @author Jeanfrancois Arcand
 */
public class ReflectorServletProcessor extends AbstractReflectorAtmosphereHandler {

    private static final long serialVersionUID = 7526472295622776148L;
    private final static String APPLICATION_NAME = "applicationClassName";
    private static final Logger logger = LoggerFactory.getLogger(ReflectorServletProcessor.class);

    private String servletClassName;
    private final HashMap<String, String> filtersClassAndNames = new HashMap<String, String>();
    private final HashSet<Filter> filters = new HashSet<Filter>();
    private final FilterChainServletWrapper wrapper = new FilterChainServletWrapper();
    private final AtmosphereFilterChain filterChain = new AtmosphereFilterChain();
    private Servlet servlet;
    private AtmosphereConfig config;

    public ReflectorServletProcessor() {
    }

    public ReflectorServletProcessor(Servlet servlet) {
        this.servlet = servlet;
    }

    void loadWebApplication(ServletConfig sc) throws Exception {

        URL url = sc.getServletContext().getResource("/WEB-INF/lib/");
        URLClassLoader urlC = new URLClassLoader(new URL[]{url},
                Thread.currentThread().getContextClassLoader());

        loadServlet(sc, urlC);
        if (!filters.isEmpty()) {
            loadFilterInstances(sc);
        } else {
            loadFilterClasses(sc, urlC);
        }
    }

    private void loadServlet(ServletConfig sc, URLClassLoader urlC) throws Exception {
        if (servletClassName != null && servlet == null) {
            try {
                servlet = config.framework().newClassInstance(Servlet.class, (Class<Servlet>) urlC.loadClass(servletClassName));
            } catch (NullPointerException ex) {
                // We failed to load the servlet, let's try directly.
                servlet = config.framework().newClassInstance(Servlet.class,
                        (Class<Servlet>) IOUtils.loadClass(getClass(), servletClassName));

            }
        }

        logger.info("Installing Servlet {}", servletClassName == null ? servlet.getClass().getName() : servletClassName);
    }

    private void loadFilterClasses(ServletConfig sc, URLClassLoader urlC) throws Exception {

        for (Map.Entry<String, String> fClassAndName : filtersClassAndNames.entrySet()) {
            String fClass = fClassAndName.getKey();
            String filterName = fClassAndName.getValue();
            Filter f = loadFilter(urlC, fClass);
            if (filterName == null) {
                if (sc.getInitParameter(APPLICATION_NAME) != null) {
                    filterName = sc.getInitParameter(APPLICATION_NAME);
                } else {
                    filterName = f.getClass().getSimpleName();
                }
            }
            FilterConfigImpl fc = new FilterConfigImpl(sc);
            fc.setFilter(f);
            fc.setFilterName(filterName);
            filterChain.addFilter(fc);
            logger.info("Installing Filter {}", filterName);
        }
    }

    private Filter loadFilter(URLClassLoader urlC, String fClass) throws Exception {
        Filter f;
        try {
            f = config.framework().newClassInstance(Filter.class, (Class<Filter>) urlC.loadClass(fClass));
        } catch (NullPointerException ex) {
            // We failed to load the Filter, let's try directly.
            f = config.framework().newClassInstance(Filter.class, (Class<Filter>) IOUtils.loadClass(getClass(), fClass));
        }
        return f;
    }

    private void loadFilterInstances(ServletConfig sc) {
        for (Filter f : filters) {
            FilterConfigImpl fc = new FilterConfigImpl(sc);
            fc.setFilter(f);
            fc.setFilterName(f.getClass().getSimpleName());
            filterChain.addFilter(fc);
            logger.info("Installing Filter {}", f.getClass().getSimpleName());
        }
    }

    /**
     * Delegate the request to the Servlet.service method, and add the {@link AtmosphereResource}
     * to the {@link HttpServletRequest#setAttribute(java.lang.String, java.lang.Object))}.
     * The {@link AtmosphereResource} can ve retrieved using {@link org.atmosphere.cpr.FrameworkConfig#ATMOSPHERE_RESOURCE}
     * value.
     *
     * @param r The {@link AtmosphereResource}
     * @throws java.io.IOException
     */
    public void onRequest(AtmosphereResource r)
            throws IOException {
        try {
            wrapper.service(r.getRequest(), r.getResponse());
        } catch (Throwable ex) {
            logger.error("onRequest()", ex);
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void init(AtmosphereConfig config) throws ServletException {
        this.config = config;
        try {
            loadWebApplication(config.getServletConfig());
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
        filterChain.setServlet(config.getServletConfig(), servlet);
        wrapper.init(config.getServletConfig());
    }

    public void addFilter(Filter filter) {
        filters.add(filter);
    }

    @Override
    public void destroy() {
        filterChain.destroy();
    }

    /**
     * Set the Servlet class.
     *
     * @return the servletClass
     * @deprecated - use getServletClassName
     */
    @Deprecated
    public String getServletClass() {
        return servletClassName;
    }

    /**
     * Return the Servlet class name.
     *
     * @param servletClass the servletClass to set
     * @deprecated - use setServletClassName
     */
    @Deprecated
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
     *
     * @param filterClass
     */
    public void setFilterClassName(String filterClass) {
        if (filterClass == null) return;
        filtersClassAndNames.put(filterClass, filterClass);
    }

    /**
     * Add a FilterClass. Since we are using Reflection to call this method,
     * what we are really doing is addFilterClass.
     *
     * @param filterClass class name of the filter to instantiate.
     * @param filterName  mapping name of the filter to instantiate
     */
    public void addFilterClassName(String filterClass, String filterName) {
        if (filterClass == null || filterName == null) return;
        filtersClassAndNames.put(filterClass, filterName);
    }

    public Servlet getServlet() {
        return servlet;
    }

    public void setServlet(Servlet servlet) {
        this.servlet = servlet;
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
        public Enumeration<String> getInitParameterNames() {
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

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}