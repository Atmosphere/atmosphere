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
package org.atmosphere.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Builder and wrapper for creating a gRPC server integrated with {@link AtmosphereFramework}.
 */
public class AtmosphereGrpcServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereGrpcServer.class);

    private final Server server;
    private final GrpcProcessor processor;

    private AtmosphereGrpcServer(Server server, GrpcProcessor processor) {
        this.server = server;
        this.processor = processor;
    }

    public void start() throws IOException {
        server.start();
        logger.info("Atmosphere gRPC server started on port {}", server.getPort());
    }

    public void stop() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
            logger.info("Atmosphere gRPC server stopped");
        }
    }

    public void awaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public int port() {
        return server.getPort();
    }

    public GrpcProcessor processor() {
        return processor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private AtmosphereFramework framework;
        private int port = 9090;
        private GrpcHandler handler = new GrpcHandlerAdapter();
        private boolean enableReflection = true;
        private final List<ServerInterceptor> interceptors = new ArrayList<>();

        public Builder framework(AtmosphereFramework framework) {
            this.framework = framework;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder handler(GrpcHandler handler) {
            this.handler = handler;
            return this;
        }

        public Builder enableReflection(boolean enableReflection) {
            this.enableReflection = enableReflection;
            return this;
        }

        public Builder interceptor(ServerInterceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        @SuppressWarnings("deprecation")
        public AtmosphereGrpcServer build() throws IOException {
            if (framework == null) {
                throw new IllegalStateException("AtmosphereFramework must be set");
            }

            if (!framework.initialized()) {
                framework.init();
            }

            var processor = new GrpcProcessor(framework, handler);
            var service = new AtmosphereGrpcService(processor);

            var serverBuilder = ServerBuilder.forPort(port)
                    .addService(service);

            if (enableReflection) {
                serverBuilder.addService(ProtoReflectionService.newInstance());
            }

            for (var interceptor : interceptors) {
                serverBuilder.intercept(interceptor);
            }

            var server = serverBuilder.build();
            return new AtmosphereGrpcServer(server, processor);
        }
    }
}
