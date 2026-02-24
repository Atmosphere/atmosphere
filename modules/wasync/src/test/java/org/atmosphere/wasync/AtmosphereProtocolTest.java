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
package org.atmosphere.wasync;

import org.atmosphere.wasync.impl.AtmosphereClient;
import org.atmosphere.wasync.impl.AtmosphereRequestBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Atmosphere protocol integration.
 */
class AtmosphereProtocolTest {

    @Test
    void atmosphereClientCreatesSocket() {
        var client = AtmosphereClient.newClient();
        var socket = client.create();
        assertNotNull(socket);
        assertEquals(Socket.STATUS.INIT, socket.status());
    }

    @Test
    void atmosphereRequestBuilderAddsProtocolParams() {
        var client = AtmosphereClient.newClient();
        var builder = (AtmosphereRequestBuilder) client.newRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(true)
                .build();

        assertEquals("ws://localhost:8080/chat", request.uri());

        var qs = request.queryString();
        assertTrue(qs.containsKey("X-Atmosphere-Framework"));
        assertTrue(qs.containsKey("X-Atmosphere-tracking-id"));
        assertTrue(qs.containsKey("X-atmo-protocol"));
        assertEquals(List.of("websocket"), qs.get("X-Atmosphere-Transport"));
    }

    @Test
    void atmosphereRequestBuilderTrackMessageLength() {
        var builder = new AtmosphereRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(true)
                .trackMessageLength(true)
                .build();

        var qs = request.queryString();
        assertEquals(List.of("true"), qs.get("X-Atmosphere-TrackMessageSize"));

        // Should have decoders: PaddingAndHeartbeat + TrackMessageSize + AtmosphereProtocol
        assertTrue(request.decoders().size() >= 3);
    }

    @Test
    void atmosphereRequestBuilderDisableProtocol() {
        var builder = new AtmosphereRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var qs = request.queryString();
        assertNotNull(qs.get("X-Atmosphere-Framework"));
        // Protocol disabled — no X-atmo-protocol param
        assertTrue(!qs.containsKey("X-atmo-protocol"));
        // Should have PaddingAndHeartbeat decoder only
        assertEquals(1, request.decoders().size());
    }
}
