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
package org.atmosphere.samples.grpc;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dual-port chat server: native gRPC on port 9090 and Connect protocol on port 8080.
 *
 * <p>Both transports share the same {@link AtmosphereFramework} and Broadcaster,
 * so gRPC CLI clients and browser clients see each other's messages.
 *
 * <p>Run with: {@code mvn exec:java -pl samples/grpc-chat}
 */
public class GrpcChatServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcChatServer.class);

    public static void main(String[] args) throws Exception {
        var framework = new AtmosphereFramework();
        framework.setBroadcasterCacheClassName(
                "org.atmosphere.cache.UUIDBroadcasterCache");

        try (var grpcServer = AtmosphereGrpcServer.builder()
                .framework(framework)
                .port(9090)
                .handler(new ChatHandler())
                .build()) {

            grpcServer.start();
            logger.info("gRPC server listening on port {}", grpcServer.port());

            // Start Jetty HTTP server for the Connect protocol and static frontend
            var jettyServer = startJettyServer(grpcServer);
            logger.info("Connect protocol + frontend on http://localhost:{}",
                    ((ServerConnector) jettyServer.getConnectors()[0]).getPort());

            grpcServer.awaitTermination();
        }
    }

    private static Server startJettyServer(AtmosphereGrpcServer grpcServer) throws Exception {
        var server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(Integer.getInteger("http.port", 8080));
        server.addConnector(connector);

        var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Resolve the built frontend directory
        var webappPath = resolveWebappPath();
        if (webappPath != null) {
            context.setBaseResource(ResourceFactory.root().newResource(webappPath.toUri()));
            logger.info("Serving frontend from {}", webappPath);
        } else {
            logger.warn("No frontend build found. Run 'npm run build' in samples/grpc-chat/frontend/");
        }

        // Mount Connect protocol servlet at the standard Connect path
        var connectServlet = new ServletHolder("connect",
                new ConnectProtocolServlet(grpcServer.processor()));
        connectServlet.setAsyncSupported(true);
        context.addServlet(connectServlet, "/org.atmosphere.grpc.AtmosphereService/*");

        // Serve static frontend files
        var defaultServlet = new ServletHolder("default", DefaultServlet.class);
        defaultServlet.setInitParameter("dirAllowed", "false");
        context.addServlet(defaultServlet, "/*");

        server.setHandler(context);
        server.start();
        return server;
    }

    private static Path resolveWebappPath() {
        // Locate the project directory from the classpath (target/classes is always there)
        var classesUrl = GrpcChatServer.class.getProtectionDomain().getCodeSource().getLocation();
        Path projectDir;
        try {
            // classesUrl is typically file:.../samples/grpc-chat/target/classes/
            projectDir = Path.of(classesUrl.toURI()).getParent().getParent();
        } catch (Exception e) {
            projectDir = Path.of(".");
        }

        for (var candidate : new String[]{"src/main/webapp", "frontend/dist"}) {
            var path = projectDir.resolve(candidate);
            if (Files.exists(path.resolve("index.html"))) {
                return path;
            }
        }

        // Fallback: try relative to CWD
        for (var candidate : new String[]{"samples/grpc-chat/src/main/webapp",
                "samples/grpc-chat/frontend/dist", "src/main/webapp", "frontend/dist"}) {
            var path = Path.of(candidate).toAbsolutePath();
            if (Files.exists(path.resolve("index.html"))) {
                return path;
            }
        }
        return null;
    }
}
