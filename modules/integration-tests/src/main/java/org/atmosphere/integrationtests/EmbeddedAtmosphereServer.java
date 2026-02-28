/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.integrationtests;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.util.HashMap;
import java.util.Map;

/**
 * Embedded Jetty server for integration testing.
 * Starts Atmosphere on a random port with configurable properties.
 */
@SuppressWarnings("try")
public class EmbeddedAtmosphereServer implements AutoCloseable {

    private final Server server;
    private final ServerConnector connector;
    private final Map<String, String> initParams = new HashMap<>();
    private String annotationPackage = "org.atmosphere.integrationtests";
    private AtmosphereServlet atmosphereServlet;

    public EmbeddedAtmosphereServer() {
        server = new Server();
        connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        // Disable auto-detection of clustered broadcasters (Redis/Kafka) on classpath
        initParams.put(ApplicationConfig.AUTODETECT_BROADCASTER, "false");
    }

    public EmbeddedAtmosphereServer withPort(int port) {
        connector.setPort(port);
        return this;
    }

    public EmbeddedAtmosphereServer withAnnotationPackage(String pkg) {
        this.annotationPackage = pkg;
        return this;
    }

    public EmbeddedAtmosphereServer withInitParam(String key, String value) {
        initParams.put(key, value);
        return this;
    }

    public void start() throws Exception {
        var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        JakartaWebSocketServletContainerInitializer.configure(context, null);

        atmosphereServlet = new AtmosphereServlet();
        var holder = new ServletHolder(atmosphereServlet);
        holder.setInitParameter(ApplicationConfig.ANNOTATION_PACKAGE, annotationPackage);
        holder.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true");

        for (var entry : initParams.entrySet()) {
            holder.setInitParameter(entry.getKey(), entry.getValue());
        }

        holder.setAsyncSupported(true);
        context.addServlet(holder, "/*");

        server.setHandler(context);
        server.start();
    }

    public int getPort() {
        return connector.getLocalPort();
    }

    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    public String getWebSocketUrl() {
        return "ws://localhost:" + getPort();
    }

    public org.atmosphere.cpr.AtmosphereFramework getFramework() {
        return atmosphereServlet.framework();
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }
}
