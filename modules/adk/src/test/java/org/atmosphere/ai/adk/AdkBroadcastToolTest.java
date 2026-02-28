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
package org.atmosphere.ai.adk;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdkBroadcastToolTest {

    @Test
    void broadcastToFixedBroadcaster() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/chat");
        @SuppressWarnings("unchecked")
        Collection<AtmosphereResource> resources = (Collection<AtmosphereResource>) mock(Collection.class);
        when(resources.size()).thenReturn(3);
        when(broadcaster.getAtmosphereResources()).thenReturn(resources);

        var tool = new AdkBroadcastTool(broadcaster);
        var result = tool.runAsync(Map.of("message", "Hello!"), null).blockingGet();

        assertEquals("success", result.get("status"));
        assertEquals("/chat", result.get("topic"));
        assertEquals(3, result.get("recipients"));
        verify(broadcaster).broadcast("Hello!");
    }

    @Test
    void broadcastWithTopicFromArgs() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/notifications");
        @SuppressWarnings("unchecked")
        Collection<AtmosphereResource> resources = (Collection<AtmosphereResource>) mock(Collection.class);
        when(resources.size()).thenReturn(1);
        when(broadcaster.getAtmosphereResources()).thenReturn(resources);

        var factory = mock(BroadcasterFactory.class);
        when(factory.lookup("/notifications", true)).thenReturn(broadcaster);

        var tool = new AdkBroadcastTool(factory);
        var result = tool.runAsync(Map.of("message", "Alert!", "topic", "/notifications"), null).blockingGet();

        assertEquals("success", result.get("status"));
        verify(factory).lookup("/notifications", true);
        verify(broadcaster).broadcast("Alert!");
    }

    @Test
    void emptyMessageReturnsError() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/chat");
        var tool = new AdkBroadcastTool(broadcaster);

        var result = tool.runAsync(Map.of("message", ""), null).blockingGet();
        assertEquals("error", result.get("status"));
    }

    @Test
    void missingMessageReturnsError() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/chat");
        var tool = new AdkBroadcastTool(broadcaster);

        var result = tool.runAsync(Map.of(), null).blockingGet();
        assertEquals("error", result.get("status"));
    }

    @Test
    void declarationHasCorrectSchema() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/chat");
        var tool = new AdkBroadcastTool(broadcaster);

        assertTrue(tool.declaration().isPresent());
        assertEquals("broadcast", tool.declaration().get().name().get());
    }

    @Test
    void factoryBasedDeclarationIncludesTopic() {
        var factory = mock(BroadcasterFactory.class);
        var tool = new AdkBroadcastTool(factory);

        assertTrue(tool.declaration().isPresent());
        var params = tool.declaration().get().parameters();
        assertTrue(params.isPresent());
        var properties = params.get().properties();
        assertTrue(properties.isPresent());
        assertTrue(properties.get().containsKey("topic"));
        assertTrue(properties.get().containsKey("message"));
    }

    @Test
    void fixedBroadcasterDeclarationOmitsTopic() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/chat");
        var tool = new AdkBroadcastTool(broadcaster);

        assertTrue(tool.declaration().isPresent());
        var params = tool.declaration().get().parameters();
        assertTrue(params.isPresent());
        var properties = params.get().properties();
        assertTrue(properties.isPresent());
        assertFalse(properties.get().containsKey("topic"));
        assertTrue(properties.get().containsKey("message"));
    }
}
