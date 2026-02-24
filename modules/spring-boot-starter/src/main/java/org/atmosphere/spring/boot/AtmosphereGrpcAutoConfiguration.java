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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.atmosphere.grpc.GrpcHandler;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/**
 * Auto-configuration for the Atmosphere gRPC transport.
 * Activates when {@code atmosphere-grpc} is on the classpath and
 * {@code atmosphere.grpc.enabled=true} is set.
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(AtmosphereGrpcServer.class)
@ConditionalOnProperty(name = "atmosphere.grpc.enabled", havingValue = "true")
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereGrpcAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereGrpcAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GrpcHandler grpcHandler() {
        return new GrpcHandlerAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereGrpcServer atmosphereGrpcServer(AtmosphereFramework framework,
                                                     GrpcHandler handler,
                                                     AtmosphereProperties properties) throws IOException {
        var grpcProps = properties.getGrpc();
        return AtmosphereGrpcServer.builder()
                .framework(framework)
                .port(grpcProps.getPort())
                .handler(handler)
                .enableReflection(grpcProps.isEnableReflection())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "atmosphereGrpcLifecycle")
    public SmartLifecycle atmosphereGrpcLifecycle(AtmosphereGrpcServer grpcServer) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    grpcServer.start();
                    running = true;
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to start Atmosphere gRPC server", e);
                }
            }

            @Override
            public void stop() {
                grpcServer.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                // Start after the servlet container (default phase 0)
                return Integer.MAX_VALUE - 1;
            }
        };
    }
}
