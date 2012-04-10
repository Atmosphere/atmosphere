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
package org.atmosphere.tests.websocket;

import org.atmosphere.container.JettyAsyncSupportWithWebSocket;
import org.atmosphere.cpr.AtmosphereServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

public class JettyWebSocketSupportTest extends BaseTest {
    protected Server server;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = findFreePort();
        urlTarget = "ws://127.0.0.1:" + port + "/invoke";

        server = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        atmoServlet = new AtmosphereServlet();
        configureCometSupport();
        context.addServlet(new ServletHolder(atmoServlet), "/");
        server.start();
    }

    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new JettyAsyncSupportWithWebSocket(atmoServlet.framework().getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.framework().destroy();
        server.stop();
        server = null;
    }

}