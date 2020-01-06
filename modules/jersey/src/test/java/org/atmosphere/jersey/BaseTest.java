/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.jersey;

import org.atmosphere.container.Jetty7CometSupport;
import org.atmosphere.cpr.AtmosphereServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.io.IOException;
import java.net.ServerSocket;

public abstract class BaseTest {

    protected static final Logger logger = LoggerFactory.getLogger(BaseTest.class);

    protected static final String ROOT = "/*";

    protected AtmosphereServlet atmoServlet;
    public String urlTarget;
    public int port;
    private Server server;

    public void startServer() throws Exception {
        server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(atmoServlet), "/*");
        server.start();
    }

    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new Jetty7CometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    public void stopServer() throws Exception {
        server.stop();
    }

    public static class TestHelper {

        public static int getEnvVariable(final String varName, int defaultValue) {
            if (null == varName) {
                return defaultValue;
            }
            String varValue = System.getenv(varName);
            if (null != varValue) {
                try {
                    return Integer.parseInt(varValue);
                } catch (NumberFormatException e) {
                    // will return default value bellow
                }
            }
            return defaultValue;
        }
    }

    protected int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            socket = new ServerSocket(0);

            return socket.getLocalPort();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @BeforeMethod(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = getUrlTarget(port);
        atmoServlet = new AtmosphereServlet();
        atmoServlet.framework().addInitParameter("com.sun.jersey.config.property.packages", this.getClass().getPackage().getName());

        configureCometSupport();
        startServer();
    }

    abstract String getUrlTarget(int port);

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        if (atmoServlet != null) atmoServlet.destroy();
        stopServer();
    }


}
