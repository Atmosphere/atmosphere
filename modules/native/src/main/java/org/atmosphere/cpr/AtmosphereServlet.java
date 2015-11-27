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

import org.apache.catalina.CometEvent;
import org.apache.catalina.CometProcessor;
import org.atmosphere.container.JBossAsyncSupportWithWebSocket;
import org.atmosphere.container.JBossWebCometSupport;
import org.atmosphere.container.Tomcat7CometSupport;
import org.atmosphere.container.TomcatCometSupport;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.servlet.http.HttpEventServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;

/**
 * This servlet supports native Comet support with Tomcat 6, 7 and JBoss Web 3.x
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereServlet extends HttpServlet implements CometProcessor, HttpEventServlet, org.apache.catalina.comet.CometProcessor {

    private static final long serialVersionUID = 7526472295622776147L;
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
        initializer.configureFramework(sc, init, true, AtmosphereFramework.class);
        return this;
    }

    protected AtmosphereFramework newAtmosphereFramework() {
        return initializer.newAtmosphereFramework(AtmosphereFramework.class);
    }

    public AtmosphereFramework framework() {
       return initializer.framework();
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        initializer.framework().doCometSupport(AtmosphereRequestImpl.wrap(req), AtmosphereResponseImpl.wrap(res));
    }

    /**
     * Hack to support Tomcat AIO like other WebServer. This method is invoked
     * by Tomcat when it detect a {@link Servlet} implements the interface
     * {@link CometProcessor} without invoking {@link Servlet#service}
     *
     * @param cometEvent the {@link CometEvent}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void event(CometEvent cometEvent) throws IOException, ServletException {
        HttpServletRequest req = cometEvent.getHttpServletRequest();
        HttpServletResponse res = cometEvent.getHttpServletResponse();
        req.setAttribute(TomcatCometSupport.COMET_EVENT, cometEvent);

        if (!initializer.framework().getAsyncSupport().supportWebSocket()) {
            if (!initializer.framework().isCometSupportSpecified && !initializer.framework().isCometSupportConfigured.getAndSet(true)) {
                synchronized (initializer.framework().asyncSupport) {
                    if (!initializer.framework().asyncSupport.getClass().equals(TomcatCometSupport.class)) {
                        AsyncSupport current = initializer.framework().asyncSupport;
                        logger.warn("TomcatCometSupport is enabled, switching to it");
                        initializer.framework().asyncSupport = new TomcatCometSupport(initializer.framework().config);
                        if (current instanceof AsynchronousProcessor) {
                            ((AsynchronousProcessor) current).shutdown();
                        }
                        initializer.framework().asyncSupport.init(initializer.framework().config.getServletConfig());
                    }
                }
            }
        }

        initializer.framework().doCometSupport(AtmosphereRequestImpl.wrap(req), AtmosphereResponseImpl.wrap(res));

        String transport = cometEvent.getHttpServletRequest().getParameter(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        if (transport != null && transport.equalsIgnoreCase(HeaderConfig.WEBSOCKET_TRANSPORT)) {
            cometEvent.close();
        }
    }

    /**
     * Hack to support Tomcat 7 AIO
     */
    public void event(org.apache.catalina.comet.CometEvent cometEvent) throws IOException, ServletException {
        HttpServletRequest req = cometEvent.getHttpServletRequest();
        HttpServletResponse res = cometEvent.getHttpServletResponse();
        req.setAttribute(Tomcat7CometSupport.COMET_EVENT, cometEvent);

        if (!initializer.framework().getAsyncSupport().supportWebSocket()) {
            if (!initializer.framework().isCometSupportSpecified && !initializer.framework().isCometSupportConfigured.getAndSet(true)) {
                synchronized (initializer.framework().asyncSupport) {
                    if (!initializer.framework().asyncSupport.getClass().equals(Tomcat7CometSupport.class)) {
                        AsyncSupport current = initializer.framework().asyncSupport;
                        logger.warn("TomcatCometSupport7 is enabled, switching to it");
                        initializer.framework().asyncSupport = new Tomcat7CometSupport(initializer.framework().config);
                        if (current instanceof AsynchronousProcessor) {
                            ((AsynchronousProcessor) current).shutdown();
                        }
                        initializer.framework().asyncSupport.init(initializer.framework().config.getServletConfig());
                    }
                }
            }
        }

        initializer.framework().doCometSupport(AtmosphereRequestImpl.wrap(req), AtmosphereResponseImpl.wrap(res));

        // https://github.com/Atmosphere/atmosphere/issues/920
        String transport = cometEvent.getHttpServletRequest().getParameter(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        boolean webSocketSupported = (transport != null && transport.equalsIgnoreCase(HeaderConfig.WEBSOCKET_TRANSPORT));
        if (!webSocketSupported) {
            try {
                Enumeration<String> connection = req.getHeaders("Connection");
                if (connection != null && connection.hasMoreElements()) {
                    String[] e = connection.nextElement().toString().split(",");
                    for (String upgrade : e) {
                        if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                            webSocketSupported = true;
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                logger.trace("", ex);
            }
        }

        if (webSocketSupported) {
            cometEvent.close();
        }
    }

    /**
     * Hack to support JBossWeb AIO like other WebServer. This method is invoked
     * by Tomcat when it detect a {@link Servlet} implements the interface
     * {@link HttpEventServlet} without invoking {@link Servlet#service}
     *
     * @param httpEvent the {@link CometEvent}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void event(HttpEvent httpEvent) throws IOException, ServletException {
        HttpServletRequest req = httpEvent.getHttpServletRequest();
        HttpServletResponse res = httpEvent.getHttpServletResponse();
        req.setAttribute(JBossWebCometSupport.HTTP_EVENT, httpEvent);

        if (!initializer.framework().isCometSupportSpecified && !initializer.framework().isCometSupportConfigured.getAndSet(true)) {
            synchronized (initializer.framework().asyncSupport) {
                if (!initializer.framework().asyncSupport.getClass().equals(JBossWebCometSupport.class)
                        && !initializer.framework().asyncSupport.getClass().equals(JBossAsyncSupportWithWebSocket.class)) {
                    AsyncSupport current = initializer.framework().asyncSupport;
                    logger.warn("JBossWebCometSupport is enabled, switching to it");
                    initializer.framework().asyncSupport = new JBossWebCometSupport(initializer.framework().config);
                    if (current instanceof AsynchronousProcessor) {
                        ((AsynchronousProcessor) current).shutdown();
                    }
                    initializer.framework().asyncSupport.init(initializer.framework().config.getServletConfig());
                }
            }
        }

        boolean isWebSocket = req.getHeader("Upgrade") == null ? false : true;
        if (isWebSocket && initializer.framework().asyncSupport.getClass().equals(JBossAsyncSupportWithWebSocket.class)) {
            logger.trace("Dispatching websocket event: " + httpEvent);
            ((JBossAsyncSupportWithWebSocket) initializer.framework().asyncSupport).dispatch(httpEvent);
        } else {
            logger.trace("Dispatching comet event: " + httpEvent);
            initializer.framework().doCometSupport(AtmosphereRequestImpl.wrap(req), AtmosphereResponseImpl.wrap(res));
        }
    }

}
