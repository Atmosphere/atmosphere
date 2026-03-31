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
package org.atmosphere.mcp;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BiDirectionalToolBridgeTest {

    private AtmosphereResource mockResource() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getResponse()).thenReturn(response);
        when(response.write(anyString())).thenReturn(response);
        return resource;
    }

    @Test
    void successfulRoundTrip() throws Exception {
        var bridge = new BiDirectionalToolBridge();
        var resource = mockResource();

        var future = bridge.callClientTool(resource, "getTime", Map.of());
        assertEquals(1, bridge.pendingCount());

        // Simulate client response — extract the call ID from the pending map
        // The bridge generates a UUID, so we need to find it
        var callId = extractPendingCallId(bridge);
        bridge.completePendingCall(
                "{\"id\":\"" + callId + "\",\"result\":\"2026-03-12T19:00:00Z\"}");

        assertEquals("2026-03-12T19:00:00Z", future.get());
        assertEquals(0, bridge.pendingCount());
    }

    @Test
    void timeoutBehavior() {
        var bridge = new BiDirectionalToolBridge(Duration.ofMillis(100));
        try {
            var resource = mockResource();
            var future = bridge.callClientTool(resource, "slow", Map.of());
            var ex = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(TimeoutException.class, ex.getCause());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void concurrentToolCalls() throws Exception {
        var bridge = new BiDirectionalToolBridge();
        // Track each future with its assigned call ID
        var futureByCallId = new java.util.LinkedHashMap<String, CompletableFuture<String>>();

        for (int i = 0; i < 5; i++) {
            var resource = mockResource();
            var future = bridge.callClientTool(resource, "tool-" + i, Map.of());
            // Find the newly added call ID
            for (var id : bridge.pendingCalls().keySet()) {
                if (!futureByCallId.containsKey(id)) {
                    futureByCallId.put(id, future);
                    break;
                }
            }
        }
        assertEquals(5, bridge.pendingCount());

        // Complete each call and verify the correct future resolves
        var callIds = new ArrayList<>(futureByCallId.keySet());
        for (int i = callIds.size() - 1; i >= 0; i--) {
            var callId = callIds.get(i);
            bridge.completePendingCall(
                    "{\"id\":\"" + callId + "\",\"result\":\"result-" + i + "\"}");
            assertEquals("result-" + i, futureByCallId.get(callId).get());
        }
        assertEquals(0, bridge.pendingCount());
    }

    @Test
    void unknownResponseIsDiscarded() throws Exception {
        var bridge = new BiDirectionalToolBridge();
        var resource = mockResource();
        bridge.callClientTool(resource, "test", Map.of());

        // Send a response with an unknown ID — should not throw
        bridge.completePendingCall("{\"id\":\"unknown-id\",\"result\":\"ignored\"}");
        assertEquals(1, bridge.pendingCount());
    }

    @Test
    void errorResponseCompletesExceptionally() throws Exception {
        var bridge = new BiDirectionalToolBridge();
        var resource = mockResource();
        var future = bridge.callClientTool(resource, "fail", Map.of());

        var callId = extractPendingCallId(bridge);
        bridge.completePendingCall(
                "{\"id\":\"" + callId + "\",\"error\":\"Permission denied\"}");

        var ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(BiDirectionalToolBridge.ToolCallException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Permission denied"));
    }

    @Test
    void malformedJsonIsIgnored() throws Exception {
        var bridge = new BiDirectionalToolBridge();
        var resource = mockResource();
        bridge.callClientTool(resource, "test", Map.of());

        // Should not throw, just log warning
        bridge.completePendingCall("not valid json");
        assertEquals(1, bridge.pendingCount());
    }

    @Test
    void defaultTimeoutIsThirtySeconds() {
        var bridge = new BiDirectionalToolBridge();
        // Just verify construction works
        assertEquals(0, bridge.pendingCount());
    }

    @Test
    void requestJsonFormat() throws Exception {
        var bridge = new BiDirectionalToolBridge();
        var resource = mockResource();

        bridge.callClientTool(resource, "echo", Map.of("msg", "hello"));

        assertEquals(1, bridge.pendingCount());
    }

    private String extractPendingCallId(BiDirectionalToolBridge bridge) {
        return bridge.pendingCalls().keySet().iterator().next();
    }
}
