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
package org.atmosphere.jersey.tests;

import org.atmosphere.container.Jetty7CometSupport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class Jetty7JerseyTest extends BasePubSubTest {
    protected Server server;

    @Override
    public void startServer() throws Exception {
        server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(atmoServlet), "/*");
        server.start();
    }

    @Override
    public void configureCometSupport() {
        atmoServlet.setCometSupport(new Jetty7CometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @Override
    public void stopServer() throws Exception {
        server.stop();
    }
}