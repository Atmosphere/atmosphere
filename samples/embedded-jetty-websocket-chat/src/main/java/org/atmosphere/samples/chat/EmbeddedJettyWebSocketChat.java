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
package org.atmosphere.samples.chat;

import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class EmbeddedJettyWebSocketChat {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedJettyWebSocketChat.class);

    public static void main(String[] args) throws Exception {
        new EmbeddedJettyWebSocketChat().run();
    }

    private void run() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(Integer.getInteger("server.port", 8080));
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Use target/webapp first (contains all dependencies), fallback to src/main/webapp,
        // then classpath (fat JAR). Bind the ResourceFactory to the server's lifecycle so
        // the JAR mount is released on shutdown — ResourceFactory.root() is the JVM-scoped
        // singleton and logs "Leaked Mount" at startup when we pin a jar:!/webapp/ URL to it.
        var resourceFactory = ResourceFactory.of(server);
        Path resourceBasePath = Paths.get("target/webapp").toAbsolutePath();
        boolean filesystemMode = false;
        if (!Files.exists(resourceBasePath)) {
            resourceBasePath = Paths.get("src/main/webapp").toAbsolutePath();
        }
        if (Files.exists(resourceBasePath)) {
            context.setBaseResource(resourceFactory.newResource(resourceBasePath.toUri()));
            filesystemMode = true;
        } else {
            // Running from fat JAR — serve static files from classpath /webapp/
            var classpathResource = getClass().getClassLoader().getResource("webapp/");
            if (classpathResource != null) {
                context.setBaseResource(resourceFactory.newResource(classpathResource.toURI()));
            } else {
                log.error("webapp directory not found on filesystem or classpath");
                throw new IllegalStateException("No webapp directory found");
            }
        }

        // Add DefaultServlet to serve static content
        ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class);
        defaultServlet.setInitParameter("dirAllowed", "true");
        defaultServlet.setInitParameter("welcomeServlets", "true");
        defaultServlet.setInitParameter("redirectWelcome", "true");
        context.addServlet(defaultServlet, "/*");

        // Configure WebSocket FIRST - must be configured before AtmosphereServlet init
        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, serverContainer) -> {
            // ServerContainer is now available in ServletContext
            log.info("WebSocket ServerContainer configured: {}", serverContainer.getClass().getName());
        });

        // Add AtmosphereServlet - will auto-detect JSR356 support after WebSocket is configured  
        ServletHolder atmosphereServlet = new ServletHolder(AtmosphereServlet.class);
        atmosphereServlet.setInitParameter(ApplicationConfig.ANNOTATION_PACKAGE, "org.atmosphere.samples.chat");
        atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE, "application/json");
        atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true");
        atmosphereServlet.setInitOrder(1); // Load on startup AFTER WebSocket configured
        atmosphereServlet.setAsyncSupported(true);
        context.addServlet(atmosphereServlet, "/chat/*");

        server.setHandler(context);

        log.info("Resource base: {}", context.getBaseResource());
        if (context.getBaseResource() != null) {
            log.info("Resource base exists: {}", context.getBaseResource().exists());
            if (filesystemMode) {
                Path indexPath = resourceBasePath.resolve("index.html");
                if (Files.exists(indexPath)) {
                    log.info("index.html found at {}", indexPath);
                } else {
                    log.warn("index.html not found at {}", indexPath);
                }
            }
            // classpath mode serves from jar:.../webapp/; probing the filesystem path
            // would always miss and log a misleading WARN.
        } else {
            log.info("No file system resource base configured, using classpath only");
        }
        log.info("Default servlet path spec: /*");
        log.info("Atmosphere servlet path spec: /chat/*");
        log.info("WebSocket support added");

        server.start();
        log.info("Server started on port {}", connector.getPort());
        server.join();
    }
}