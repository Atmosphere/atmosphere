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
package org.atmosphere.samples.springboot.adkchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Atmosphere managed service that bridges ADK agent responses to WebSocket clients.
 *
 * <p>When a user sends a message, a simulated ADK event stream is created and
 * each token is written directly to the originating client's WebSocket via
 * {@link AtmosphereResource#write(String)}. This avoids the Broadcaster
 * pipeline, preventing message echo/loop issues in single-user streaming
 * scenarios.</p>
 */
@ManagedService(path = "/atmosphere/adk-chat", atmosphereConfig = {
        MAX_INACTIVE + "=120000"
})
public class AdkChat {

    private static final Logger logger = LoggerFactory.getLogger(AdkChat.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Client {} connected to ADK chat", resource.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Client {} unexpectedly disconnected", event.getResource().uuid());
        } else {
            logger.info("Client {} disconnected", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message
    public void onMessage(String userMessage) {
        logger.info("Received from {}: {}", resource.uuid(), userMessage);

        // Capture the resource reference — @ManagedService is a singleton,
        // so the field may be overwritten by another request before the
        // async RxJava callback fires.
        final AtmosphereResource client = resource;
        final AtomicLong seq = new AtomicLong(0);

        var events = DemoEventProducer.stream(userMessage);

        // Write each streaming token directly to this client's WebSocket.
        // Using resource.write() instead of Broadcaster.broadcast() to
        // avoid message echo/loop in the @ManagedService pipeline.
        events.subscribe(
                event -> event.content().ifPresent(content ->
                        content.parts().ifPresent(parts ->
                                parts.forEach(part ->
                                        part.text().ifPresent(text -> {
                                            if (!text.isEmpty()) {
                                                writeJson(client, "token", text, seq);
                                            }
                                        })
                                )
                        )
                ),
                error -> writeJson(client, "error",
                        error.getMessage() != null ? error.getMessage() : "Unknown error", seq),
                () -> writeJson(client, "complete", null, seq)
        );
    }

    private void writeJson(AtmosphereResource client, String type, String data, AtomicLong seq) {
        try {
            var msg = new LinkedHashMap<String, Object>();
            msg.put("type", type);
            if (data != null) {
                msg.put("data", data);
            }
            msg.put("seq", seq.incrementAndGet());
            client.write(MAPPER.writeValueAsString(msg));
        } catch (Exception e) {
            logger.warn("Failed to write to client {}: {}", client.uuid(), e.getMessage());
        }
    }
}
