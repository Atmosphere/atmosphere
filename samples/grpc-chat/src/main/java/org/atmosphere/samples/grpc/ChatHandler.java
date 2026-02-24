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

import org.atmosphere.grpc.GrpcChannel;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple chat handler that broadcasts messages to all connected gRPC clients.
 */
public class ChatHandler extends GrpcHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    @Override
    public void onOpen(GrpcChannel channel) {
        logger.info("Client connected: {}", channel.uuid());
        try {
            channel.write("Welcome to Atmosphere gRPC Chat! Your ID: " + channel.uuid());
        } catch (java.io.IOException e) {
            logger.warn("Failed to send welcome message to {}", channel.uuid(), e);
        }
    }

    @Override
    public void onMessage(GrpcChannel channel, String message) {
        logger.info("Message from {}: {}", channel.uuid(), message);
        // The message will be broadcast to all subscribers by GrpcProcessor
    }

    @Override
    public void onClose(GrpcChannel channel) {
        logger.info("Client disconnected: {}", channel.uuid());
    }
}
