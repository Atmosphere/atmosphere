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
package org.atmosphere.admin;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Atmosphere handler that streams {@link AdminEvent} instances to connected
 * admin dashboard clients via WebSocket/SSE. Registered at
 * {@code /atmosphere/admin/events}.
 *
 * <p>This handler eats its own dog food — the admin console connects to
 * Atmosphere using atmosphere.js to receive real-time control events.</p>
 *
 * @since 4.0
 */
public final class AdminEventHandler extends AbstractReflectorAtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdminEventHandler.class);

    /** The broadcaster ID used for admin events. */
    public static final String ADMIN_BROADCASTER_ID = "/atmosphere/admin/events";

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        logger.debug("Admin event client connected: {}", resource.uuid());
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            logger.debug("Admin event client disconnected: {}", event.getResource().uuid());
            return;
        }

        var message = event.getMessage();
        if (message != null) {
            event.getResource().write(message.toString());
        }
    }

    /**
     * Broadcast an event to all connected admin dashboard clients.
     *
     * @param factory the broadcaster factory
     * @param json    the JSON-serialized event
     */
    public static void broadcastEvent(BroadcasterFactory factory, String json) {
        if (factory == null) {
            return;
        }
        Broadcaster b = factory.lookup(ADMIN_BROADCASTER_ID, false);
        if (b != null && !b.isDestroyed()) {
            b.broadcast(json);
        }
    }
}
