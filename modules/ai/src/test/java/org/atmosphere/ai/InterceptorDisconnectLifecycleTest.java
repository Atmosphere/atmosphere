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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptorDisconnectLifecycleTest {

    @Test
    void onDisconnectDefaultIsNoOp() {
        var interceptor = new AiInterceptor() {};
        // Should not throw
        interceptor.onDisconnect("user1", "conv1", List.of());
    }

    @Test
    void onDisconnectReceivesHistory() {
        var captured = new ArrayList<List<ChatMessage>>();
        var capturedUserIds = new ArrayList<String>();

        var interceptor = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                capturedUserIds.add(userId);
                captured.add(history);
            }
        };

        var history = List.of(
                ChatMessage.user("Hello"),
                ChatMessage.assistant("Hi there!")
        );
        interceptor.onDisconnect("user42", "conv1", history);

        assertEquals(1, captured.size());
        assertEquals(2, captured.get(0).size());
        assertEquals("user42", capturedUserIds.get(0));
    }

    @Test
    void onDisconnectHandlesNullUserId() {
        var called = new boolean[]{false};
        var interceptor = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                assertNull(userId);
                called[0] = true;
            }
        };

        interceptor.onDisconnect(null, "conv1", List.of());
        assertTrue(called[0]);
    }

    @Test
    void onDisconnectHandlesEmptyHistory() {
        var called = new boolean[]{false};
        var interceptor = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                assertTrue(history.isEmpty());
                called[0] = true;
            }
        };

        interceptor.onDisconnect("user1", "conv1", List.of());
        assertTrue(called[0]);
    }

    @Test
    void multipleInterceptorsCalledInOrder() {
        var callOrder = new ArrayList<String>();

        var interceptor1 = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                callOrder.add("first");
            }
        };

        var interceptor2 = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                callOrder.add("second");
            }
        };

        var interceptors = List.of(interceptor1, interceptor2);
        for (var i : interceptors) {
            i.onDisconnect("user1", "conv1", List.of());
        }

        assertEquals(List.of("first", "second"), callOrder);
    }

    @Test
    void preAndPostProcessDefaultsStillWork() {
        var interceptor = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                // Custom disconnect logic
            }
        };

        // Verify default preProcess and postProcess still function
        var request = new AiRequest("msg", "sys", null,
                null, null, null, null, java.util.Map.of(), List.of());
        var result = interceptor.preProcess(request, null);
        assertEquals(request, result);
        // postProcess should not throw
        interceptor.postProcess(request, null);
    }
}
