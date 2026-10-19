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
package org.atmosphere.samples.mcp;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The server-status resource reports the transports its clients are connected
 * with, not a fixed description of what the server could offer.
 */
class ServerStatusTransportsTest {

    @Test
    void statusCountsTheTransportsOfTheConnectedResources() throws Exception {
        var factory = mock(AtmosphereResourceFactory.class);
        var resources = List.of(
                resource(AtmosphereResource.TRANSPORT.WEBSOCKET),
                resource(AtmosphereResource.TRANSPORT.LONG_POLLING),
                resource(AtmosphereResource.TRANSPORT.WEBSOCKET));
        when(factory.findAll()).thenReturn(resources);
        var config = mock(AtmosphereConfig.class);
        when(config.resourcesFactory()).thenReturn(factory);

        var server = new DemoMcpServer();
        var field = DemoMcpServer.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(server, config);

        var json = server.serverStatus();
        var status = new ObjectMapper().readTree(json);

        assertEquals(2, status.get("transports").get("WEBSOCKET").asInt(), json);
        assertEquals(1, status.get("transports").get("LONG_POLLING").asInt(), json);
        assertEquals(3, status.get("connectedUsers").asInt(), json);
        assertFalse(json.contains("SSE fallback"), json);
    }

    private static AtmosphereResource resource(AtmosphereResource.TRANSPORT transport) {
        var r = mock(AtmosphereResource.class);
        when(r.transport()).thenReturn(transport);
        return r;
    }
}
