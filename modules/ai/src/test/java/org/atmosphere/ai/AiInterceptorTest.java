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
import org.atmosphere.cpr.AtmosphereResource;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AiInterceptorTest {

    private final AiInterceptor defaultInterceptor = new AiInterceptor() { };

    @Test
    void defaultPreProcessReturnsSameRequest() {
        var request = new AiRequest("Hello");
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        AiRequest result = defaultInterceptor.preProcess(request, resource);
        assertSame(request, result);
    }

    @Test
    void defaultPostProcessIsNoOp() {
        var request = new AiRequest("Hello");
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        assertDoesNotThrow(() -> defaultInterceptor.postProcess(request, resource));
    }

    @Test
    void defaultOnDisconnectIsNoOp() {
        List<ChatMessage> history = List.of(ChatMessage.user("hi"));
        assertDoesNotThrow(() -> defaultInterceptor.onDisconnect("user1", "conv1", history));
    }

    @Test
    void defaultOnDisconnectAcceptsNulls() {
        assertDoesNotThrow(() -> defaultInterceptor.onDisconnect(null, null, List.of()));
    }

    @Test
    void customPreProcessCanModifyRequest() {
        AiInterceptor interceptor = new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
                return request.withMessage("Modified: " + request.message());
            }
        };

        var request = new AiRequest("Hello");
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        AiRequest result = interceptor.preProcess(request, resource);
        assertEquals("Modified: Hello", result.message());
    }

    @Test
    void customPostProcessCanObserve() {
        List<String> observed = new ArrayList<>();
        AiInterceptor interceptor = new AiInterceptor() {
            @Override
            public void postProcess(AiRequest request, AtmosphereResource resource) {
                observed.add(request.message());
            }
        };

        var request = new AiRequest("World");
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        interceptor.postProcess(request, resource);
        assertEquals(1, observed.size());
        assertEquals("World", observed.get(0));
    }

    @Test
    void customOnDisconnectReceivesHistory() {
        List<ChatMessage> captured = new ArrayList<>();
        AiInterceptor interceptor = new AiInterceptor() {
            @Override
            public void onDisconnect(String userId, String conversationId,
                                     List<ChatMessage> history) {
                captured.addAll(history);
            }
        };

        var history = List.of(
                ChatMessage.user("hi"),
                ChatMessage.assistant("hello")
        );
        interceptor.onDisconnect("u1", "c1", history);
        assertEquals(2, captured.size());
        assertEquals("user", captured.get(0).role());
        assertEquals("assistant", captured.get(1).role());
    }

    @Test
    void preProcessWithNullResourceDoesNotThrow() {
        var request = new AiRequest("test");
        assertDoesNotThrow(() -> defaultInterceptor.preProcess(request, null));
    }
}
