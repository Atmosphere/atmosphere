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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * AtmosphereServlet that use Servlet 3.0 Async API when available, and fallback to native comet support if not available.
 * For Tomcat6/7 and JBossWeb Native support, use atmosphere-native dependencies instead.
 * <p/>
 * If Servlet 3.0 or native API isn't found, Atmosphere will use {@link org.atmosphere.container.BlockingIOCometSupport}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereServlet extends HttpServlet {

    private static final long serialVersionUID = 7526472295622776146L;
    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);
    protected final AtmosphereFrameworkInitializer initializer;

    /**
     * Create an Atmosphere Servlet.
     */
    public AtmosphereServlet() {
        this(false);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link org.atmosphere.cpr.AtmosphereFilter}
     */
    public AtmosphereServlet(boolean isFilter) {
        this(isFilter, true);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter           true if this instance is used as an {@link org.atmosphere.cpr.AtmosphereFilter}
     * @param autoDetectHandlers
     */
    public AtmosphereServlet(boolean isFilter, boolean autoDetectHandlers) {
        initializer = new AtmosphereFrameworkInitializer(isFilter, autoDetectHandlers);
    }

    @Override
    public void destroy() {
        initializer.destroy();
    }

    @Override
    public void init(final ServletConfig sc) throws ServletException {
        configureFramework(sc);
        super.init(sc);
    }

    protected AtmosphereServlet configureFramework(ServletConfig sc) throws ServletException {
        return configureFramework(sc, true);
    }

    protected AtmosphereServlet configureFramework(ServletConfig sc, boolean init) throws ServletException {
        initializer.configureFramework(sc, init, false, AtmosphereFramework.class);
        return this;
    }

    protected AtmosphereFramework newAtmosphereFramework() {
        return initializer.newAtmosphereFramework(AtmosphereFramework.class);
    }

    public AtmosphereFramework framework() {
       return initializer.framework();
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doHead(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doOptions(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doTrace(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doDelete(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPut(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        framework().doCometSupport(AtmosphereRequestImpl.wrap(req), AtmosphereResponseImpl.wrap(res));
    }
}