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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent card is served at the A2A v1.0.0 well-known name
 * {@code agent-card.json} as well as the pre-1.0 {@code agent.json}, with its
 * relative interface URL made absolute against the request (A2A v1.0.0
 * requires an absolute {@code AgentInterface.url}).
 */
class A2aHandlerAgentCardTest {

    private final TaskManager taskManager = new TaskManager();

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/.well-known/agent-card.json", "/.well-known/agent.json",
            "/atmosphere/a2a/agent-card.json"})
    void theCardIsServedWithAnAbsoluteInterfaceUrl(String path) throws Exception {
        var registry = new A2aRegistry();
        var card = registry.buildAgentCard("assistant", "test", "1.0", "/atmosphere/a2a");
        var handler = new A2aHandler(new A2aProtocolHandler(registry, taskManager, card));

        var body = new StringWriter();
        var request = mock(AtmosphereRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(9117);
        when(request.getContextPath()).thenReturn("");
        var response = mock(AtmosphereResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);

        handler.handleGet(resource);

        verify(response).setStatus(200);
        assertTrue(body.toString().contains("\"url\":\"http://localhost:9117/atmosphere/a2a\""),
                body.toString());
    }

    @Test
    void bothWellKnownNamesAreCardPaths() {
        assertTrue(A2aHandler.isAgentCardPath("/.well-known/agent-card.json"));
        assertTrue(A2aHandler.isAgentCardPath("/.well-known/agent.json"));
        assertFalse(A2aHandler.isAgentCardPath("/atmosphere/a2a"));
        assertFalse(A2aHandler.isAgentCardPath(null));
    }

    @Test
    void theOriginBracketsAnIpv6HostAndOmitsDefaultPorts() {
        var request = mock(AtmosphereRequest.class);
        when(request.getScheme()).thenReturn("HTTP");
        when(request.getServerName()).thenReturn("::1");
        when(request.getServerPort()).thenReturn(80);
        when(request.getContextPath()).thenReturn("/ctx");

        assertEquals("http://[::1]/ctx", A2aHandler.origin(request));
    }
}
