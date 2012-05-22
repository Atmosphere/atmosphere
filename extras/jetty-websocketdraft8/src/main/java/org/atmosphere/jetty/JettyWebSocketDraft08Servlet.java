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
package org.atmosphere.jetty;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Jetty 7.2 and 8.0.0 WebSocket supports.
 */
public class JettyWebSocketDraft08Servlet extends WebSocketServlet {

    protected static final Logger logger = LoggerFactory.getLogger(JettyWebSocketDraft08Servlet.class);
    protected AtmosphereFramework framework;

    /**
     * Create an Atmosphere Servlet.
     */
    public JettyWebSocketDraft08Servlet() {
        this(false);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link org.atmosphere.cpr.AtmosphereFilter}
     */
    public JettyWebSocketDraft08Servlet(boolean isFilter) {
        this(isFilter, true);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link org.atmosphere.cpr.AtmosphereFilter}
     */
    public JettyWebSocketDraft08Servlet(boolean isFilter, boolean autoDetectHandlers) {
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
     *
     * @param req the {@link HttpServletRequest}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Delegate the request processing to an instance of {@link org.atmosphere.cpr.AsyncSupport}
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
     * Jetty 7.2 & 8.0.0-M1/M2and up WebSocket support.
     *
     * @param request
     * @param protocol
     * @return a {@link org.eclipse.jetty.websocket.WebSocket}}
     */
    public org.eclipse.jetty.websocket.WebSocket doWebSocketConnect(final HttpServletRequest request, final String protocol) {
        logger.debug("WebSocket upgrade requested");
        request.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
        return new JettyWebSocketDraft08Handler(AtmosphereRequest.cloneRequest(request, false,
                framework().getAtmosphereConfig().isSupportSession()), framework, framework.getWebSocketProtocol());
    }
}
