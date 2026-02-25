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

import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts both Jetty (WebSocket) and gRPC servers sharing the same
 * AtmosphereFramework. Used for cross-transport E2E testing.
 */
public class DualTransportChatServer {

    private static final Logger logger = LoggerFactory.getLogger(DualTransportChatServer.class);

    public static void main(String[] args) throws Exception {
        int httpPort = Integer.getInteger("server.port", 8080);
        int grpcPort = Integer.getInteger("grpc.port", 9090);

        var server = new EmbeddedAtmosphereServer()
                .withPort(httpPort);
        server.start();
        logger.info("Jetty started on port {}", server.getPort());

        var grpcServer = AtmosphereGrpcServer.builder()
                .framework(server.getFramework())
                .port(grpcPort)
                .handler(new GrpcHandlerAdapter())
                .build();
        grpcServer.start();
        logger.info("gRPC started on port {}", grpcServer.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            grpcServer.close();
            try {
                server.close();
            } catch (Exception e) {
                logger.warn("Error stopping Jetty", e);
            }
        }));

        Thread.currentThread().join();
    }
}
