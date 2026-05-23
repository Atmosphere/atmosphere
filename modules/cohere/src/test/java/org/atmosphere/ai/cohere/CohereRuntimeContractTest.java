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
package org.atmosphere.ai.cohere;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK test for {@link CohereAgentRuntime}. The native HTTP client
 * is replaced with a Mockito-built {@link HttpClient} returning canned SSE
 * frames so every assertion runs without touching the real Cohere API.
 */
class CohereRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    private static final String TEXT_SSE = """
            data: {"type":"message-start","id":"msg_1"}

            data: {"type":"content-start","index":0}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"Hello"}}}}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":" world"}}}}

            data: {"type":"content-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":12,"output_tokens":3}}}}

            """;

    @Override
    protected AgentRuntime createRuntime() {
        var httpClient = mockHttpClient(200, TEXT_SSE);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        return new TestableCohereRuntime(client);
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "command-a-plus-05-2026",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        // Tool-call round-trip is exercised in CohereChatClientTest with a
        // multi-round mock; skipping at the contract level so the shared TCK
        // does not need to wire a tool definition into every assertion. Tool
        // calling is still declared in capabilities() and verified by a
        // dedicated assertion below.
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        // A second runtime wired to a 500-returning HttpClient drives the
        // error path; the canned 200/SSE httpClient above can't error on
        // demand. CohereChatClientTest covers that path directly.
        return null;
    }

    @Override
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.TOKEN_USAGE,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.BUDGET_ENFORCEMENT,
                AiCapability.CONFIDENCE_SCORES,
                AiCapability.PASSIVATION,
                AiCapability.PER_REQUEST_RETRY,
                AiCapability.VISION,
                AiCapability.MULTI_MODAL);
    }

    @Test
    void runtimeNameIsCohere() {
        assertEquals("cohere", createRuntime().name());
    }

    @Test
    void runtimePriorityMatchesFrameworkConvention() {
        assertEquals(100, createRuntime().priority());
    }

    @Test
    void runtimeDeclaresToolCalling() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.TOOL_CALLING));
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockHttpClient(int statusCode, String body) {
        try {
            var httpClient = mock(HttpClient.class);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(statusCode);
            when(response.body()).thenReturn(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Test subclass that injects the mocked client directly. */
    static class TestableCohereRuntime extends CohereAgentRuntime {
        TestableCohereRuntime(CohereChatClient client) {
            setNativeClient(client);
        }

        @Override
        public boolean isAvailable() {
            // The contract test fixture wires the client directly — the
            // production "needs an API key" guard would otherwise read the
            // empty system property and return false, skipping every assertion.
            return true;
        }
    }
}
