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
package org.atmosphere.coordinator.transport;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LocalAgentTransportTest {

    @Test
    void isAvailableReturnsFalseWhenHandlerNotInFramework() {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());

        var transport = new LocalAgentTransport(framework, "test-agent", "/a2a/test");
        assertFalse(transport.isAvailable());
    }

    @Test
    void sendReturnsFailureWhenHandlerNotFound() {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());

        var transport = new LocalAgentTransport(framework, "test-agent", "/a2a/test");
        var result = transport.send("test-agent", "search", Map.of("q", "hello"));

        assertFalse(result.success());
        assertTrue(result.text().contains("Agent not found"));
    }
}
