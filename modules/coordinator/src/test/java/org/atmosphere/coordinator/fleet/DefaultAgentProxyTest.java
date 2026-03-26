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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DefaultAgentProxyTest {

    @Test
    void accessors() {
        var transport = mock(AgentTransport.class);
        var proxy = new DefaultAgentProxy("research", "1.0.0", 5, true, transport);
        assertEquals("research", proxy.name());
        assertEquals("1.0.0", proxy.version());
        assertEquals(5, proxy.weight());
        assertTrue(proxy.isLocal());
    }

    @Test
    void callDelegatesToTransport() {
        var transport = mock(AgentTransport.class);
        var expected = new AgentResult("r", "s", "ok", Map.of(), Duration.ZERO, true);
        when(transport.send("research", "search", Map.of("q", "test"))).thenReturn(expected);

        var proxy = new DefaultAgentProxy("research", "1.0.0", 1, true, transport);
        var result = proxy.call("search", Map.of("q", "test"));

        assertEquals("ok", result.text());
        verify(transport).send("research", "search", Map.of("q", "test"));
    }

    @Test
    void callAsyncReturnsFuture() {
        var transport = mock(AgentTransport.class);
        var expected = new AgentResult("r", "s", "ok", Map.of(), Duration.ZERO, true);
        when(transport.send("r", "s", Map.of())).thenReturn(expected);

        var proxy = new DefaultAgentProxy("r", "1.0.0", 1, true, transport);
        var future = proxy.callAsync("s", Map.of());

        assertNotNull(future);
        assertEquals("ok", future.join().text());
    }

    @Test
    void isAvailableDelegatesToTransport() {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);

        var proxy = new DefaultAgentProxy("r", "1.0.0", 1, true, transport);
        assertTrue(proxy.isAvailable());

        when(transport.isAvailable()).thenReturn(false);
        assertFalse(proxy.isAvailable());
    }
}
