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

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.config.service.Disconnect;

/**
 * Simple echo handler for integration tests.
 * Broadcasts every received message to all connected clients.
 */
@ManagedService(path = "/echo")
public class EchoHandler {

    @Ready
    public void onReady(AtmosphereResource r) {
        // Client connected
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        // Client disconnected
    }

    @Message
    public String onMessage(String message) {
        // Return value is broadcast to all connected clients
        return message;
    }
}
