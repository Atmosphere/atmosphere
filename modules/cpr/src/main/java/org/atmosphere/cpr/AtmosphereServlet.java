/*
 * Copyright 2012 Jeanfrancois Arcand
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


import org.apache.catalina.CometEvent;
import org.apache.catalina.CometProcessor;
import org.atmosphere.container.JBossWebCometSupport;
import org.atmosphere.container.JettyWebSocketHandler;
import org.atmosphere.container.Tomcat7CometSupport;
import org.atmosphere.container.TomcatCometSupport;
import org.atmosphere.container.WebLogicCometSupport;
import org.atmosphere.di.ServletContextProvider;
import org.atmosphere.websocket.WebSocket;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.servlet.http.HttpEventServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weblogic.servlet.http.AbstractAsyncServlet;
import weblogic.servlet.http.RequestResponseKey;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * The {@link AtmosphereServlet} acts as a dispatcher for {@link AtmosphereHandler}
 * defined in META-INF/atmosphere.xml, or if atmosphere.xml is missing, all classes
 * that implements {@link AtmosphereHandler} will be discovered and mapped using
 * the class's name.
 * <p/>
 * This {@link Servlet} can be defined inside an application's web.xml using the following:
 * <blockquote><pre>
 *  &lt;servlet&gt;
 *      &lt;description&gt;AtmosphereServlet&lt;/description&gt;
 *      &lt;servlet-name&gt;AtmosphereServlet&lt;/servlet-name&gt;
 *      &lt;servlet-class&gt;org.atmosphere.cpr.AtmosphereServlet&lt;/servlet-class&gt;
 *      &lt;load-on-startup&gt;0 &lt;/load-on-startup&gt;
 *  &lt;/servlet&gt;
 *  &lt;servlet-mapping&gt;
 *      &lt;servlet-name&gt;AtmosphereServlet&lt;/servlet-name&gt;
 *      &lt;url-pattern&gt;/Atmosphere &lt;/url-pattern&gt;
 *  &lt;/servlet-mapping&gt;
 * </pre></blockquote>
 * You can force this Servlet to use native API of the Web Server instead of
 * the Servlet 3.0 Async API you are deploying on by adding
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.useNative&lt;/param-name&gt;
 *      &lt;param-value&gt;true&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * You can force this Servlet to use one Thread per connection instead of
 * native API of the Web Server you are deploying on by adding
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.useBlocking&lt;/param-name&gt;
 *      &lt;param-value&gt;true&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * You can also define {@link Broadcaster}by adding:
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.broadcasterClass&lt;/param-name&gt;
 *      &lt;param-value&gt;class-name&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * You can also for Atmosphere to use {@link java.io.OutputStream} for all write operations.
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.useStream&lt;/param-name&gt;
 *      &lt;param-value&gt;true&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * You can also configure {@link org.atmosphere.cpr.BroadcasterCache} that persist message when Browser is disconnected.
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.broadcasterCacheClass&lt;/param-name&gt;
 *      &lt;param-value&gt;class-name&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * You can also configure Atmosphere to use http session or not
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.sessionSupport&lt;/param-name&gt;
 *      &lt;param-value&gt;false&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * You can also configure {@link BroadcastFilter} that will be applied at all newly created {@link Broadcaster}
 * <blockquote><pre>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.broadcastFilterClasses&lt;/param-name&gt;
 *      &lt;param-value&gt;BroadcastFilter class name separated by coma&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </pre></blockquote>
 * All the property available are defined in {@link ApplicationConfig}
 * The Atmosphere Framework can also be used as a Servlet Filter ({@link AtmosphereFilter}).
 * <p/>
 * If you are planning to use JSP, Servlet or JSF, you can instead use the
 * {@link MeteorServlet}, which allow the use of {@link Meteor} inside those
 * components.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereServlet extends AbstractAsyncServlet implements CometProcessor, HttpEventServlet, ServletContextProvider, org.apache.catalina.comet.CometProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);
    protected AtmosphereFramework framework;

    /**
     * Create an Atmosphere Servlet.
     */
    public AtmosphereServlet() {
        this(false);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link AtmosphereFilter}
     */
    public AtmosphereServlet(boolean isFilter) {
        this(isFilter, true);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link AtmosphereFilter}
     */
    public AtmosphereServlet(boolean isFilter, boolean autoDetectHandlers) {
        framework = new AtmosphereFramework(isFilter, autoDetectHandlers);
    }

    @Override
    public void destroy() {
        framework.destroy();
    }

    public void init(final ServletConfig sc) throws ServletException {
        super.init(sc);
        framework.init(sc);
    }

    public AtmosphereFramework framework() {
        return framework;
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doHead(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doOptions(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doTrace(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doDelete(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPut(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        framework.doCometSupport(AtmosphereRequest.wrap(req), AtmosphereResponse.wrap(res));
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

        if (!framework.isCometSupportSpecified && !framework.isCometSupportConfigured.getAndSet(true)) {
            synchronized (framework.asyncSupport) {
                if (!framework.asyncSupport.getClass().equals(TomcatCometSupport.class)) {
                    logger.warn("TomcatCometSupport is enabled, switching to it");
                    framework.asyncSupport = new TomcatCometSupport(framework.config);
                }
            }
        }

        framework.doCometSupport(AtmosphereRequest.wrap(req), AtmosphereResponse.wrap(res));
    }

    /**
     * Hack to support Tomcat 7 AIO
     */
    public void event(org.apache.catalina.comet.CometEvent cometEvent) throws IOException, ServletException {
        HttpServletRequest req = cometEvent.getHttpServletRequest();
        HttpServletResponse res = cometEvent.getHttpServletResponse();
        req.setAttribute(Tomcat7CometSupport.COMET_EVENT, cometEvent);

        if (!framework.isCometSupportSpecified && !framework.isCometSupportConfigured.getAndSet(true)) {
            synchronized (framework.asyncSupport) {
                if (!framework.asyncSupport.getClass().equals(Tomcat7CometSupport.class)) {
                    logger.warn("TomcatCometSupport is enabled, switching to it");
                    framework.asyncSupport = new Tomcat7CometSupport(framework.config);
                }
            }
        }

        framework.doCometSupport(AtmosphereRequest.wrap(req), AtmosphereResponse.wrap(res));
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

        if (!framework.isCometSupportSpecified && !framework.isCometSupportConfigured.getAndSet(true)) {
            synchronized (framework.asyncSupport) {
                if (!framework.asyncSupport.getClass().equals(JBossWebCometSupport.class)) {
                    logger.warn("JBossWebCometSupport is enabled, switching to it");
                    framework.asyncSupport = new JBossWebCometSupport(framework.config);
                }
            }
        }
        framework.doCometSupport(AtmosphereRequest.wrap(req), AtmosphereResponse.wrap(res));
    }

    /**
     * Weblogic specific comet based implementation.
     *
     * @param rrk
     * @return true if suspended
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected boolean doRequest(RequestResponseKey rrk) throws IOException, ServletException {
        try {
            rrk.getRequest().getSession().setAttribute(WebLogicCometSupport.RRK, rrk);
            AtmosphereFramework.Action action = framework.doCometSupport(AtmosphereRequest.wrap(rrk.getRequest()), AtmosphereResponse.wrap(rrk.getResponse()));
            if (action.type == AtmosphereFramework.Action.TYPE.SUSPEND) {
                if (action.timeout == -1) {
                    rrk.setTimeout(Integer.MAX_VALUE);
                } else {
                    rrk.setTimeout((int) action.timeout);
                }
            }
            return action.type == AtmosphereFramework.Action.TYPE.SUSPEND;
        } catch (IllegalStateException ex) {
            logger.error("AtmosphereServlet.doRequest exception", ex);
            throw ex;
        }
    }

    /**
     * Weblogic specific comet based implementation.
     *
     * @param rrk
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void doResponse(RequestResponseKey rrk, Object context)
            throws IOException, ServletException {
        rrk.getResponse().flushBuffer();
    }

    /**
     * Weblogic specific comet based implementation.
     *
     * @param rrk
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void doTimeout(RequestResponseKey rrk) throws IOException, ServletException {
        ((AsynchronousProcessor) framework.asyncSupport).timedout(AtmosphereRequest.wrap(rrk.getRequest()),
                AtmosphereResponse.wrap(rrk.getResponse()));
    }

    /**
     * Jetty 7.2 & 8.0.0-M1/M2and up WebSocket support.
     *
     * @param request
     * @param protocol
     * @return a {@link org.eclipse.jetty.websocket.WebSocket}}
     */
    public org.eclipse.jetty.websocket.WebSocket doWebSocketConnect(final HttpServletRequest request, final String protocol) {
        logger.debug("WebSocket upgrade requested");
        request.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
        return new JettyWebSocketHandler(AtmosphereRequest.loadInMemory(request), framework, framework.webSocketProtocol);
    }

}