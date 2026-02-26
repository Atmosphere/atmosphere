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
package org.atmosphere.samples.springboot.otelchat;

import jakarta.inject.Inject;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Chat handler with OpenTelemetry tracing.
 *
 * <p>Every WebSocket/SSE request is traced via {@code AtmosphereTracing} (auto-configured).
 * Open Jaeger at <a href="http://localhost:16686">localhost:16686</a> to see spans for
 * connect, message, and disconnect events.</p>
 */
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    private static final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Browser {} connected — trace span started", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @Message
    public String onMessage(String message) {
        logger.info("Received message — tracing active: {}", message);
        return message;
    }
}
