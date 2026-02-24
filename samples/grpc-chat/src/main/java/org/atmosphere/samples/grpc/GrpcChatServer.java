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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone gRPC chat server using Atmosphere's gRPC transport.
 *
 * <p>Run with: {@code mvn exec:java -pl samples/grpc-chat}</p>
 *
 * <p>Test with grpcurl:</p>
 * <pre>
 * grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
 *   localhost:9090 atmosphere.AtmosphereService/Stream
 * </pre>
 */
public class GrpcChatServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcChatServer.class);

    public static void main(String[] args) throws Exception {
        var framework = new AtmosphereFramework();
        framework.setBroadcasterCacheClassName(
                "org.atmosphere.cache.UUIDBroadcasterCache");

        try (var server = AtmosphereGrpcServer.builder()
                .framework(framework)
                .port(9090)
                .handler(new ChatHandler())
                .build()) {

            server.start();
            logger.info("gRPC Chat server listening on port {}", server.port());
            logger.info("Use grpcurl or a gRPC client to connect.");

            server.awaitTermination();
        }
    }
}
