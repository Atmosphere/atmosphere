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
package org.atmosphere.ai.anthropic;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK test for {@link AnthropicAgentRuntime}. The native HTTP client
 * is replaced with a Mockito-built {@link HttpClient} returning canned SSE
 * frames so every assertion runs without touching the real Anthropic API.
 */
class AnthropicRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    private static final String TEXT_SSE = """
            data: {"type":"message_start","message":{"id":"msg_1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":12,"output_tokens":3}}

            data: {"type":"message_stop"}

            """;

    @Override
    protected AgentRuntime createRuntime() {
        var httpClient = mockHttpClient(200, TEXT_SSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        return new TestableAnthropicRuntime(client);
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "claude-opus-4-7",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        // Tool-call round-trip is exercised in AnthropicMessagesClientTest
        // with a multi-round mock; skipping at the contract level so the
        // shared TCK does not need to wire a tool definition into every
        // assertion. Tool calling is still declared in capabilities() and
        // verified by a dedicated assertion below.
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        // The createRuntime() HttpClient mock inspects the outgoing request
        // body — when it spots CONTRACT_ERROR_SENTINEL it returns a 500
        // response instead of the canned SSE stream so the runtime's error
        // path (AnthropicMessagesClient.runRound -> session.error on non-2xx)
        // actually fires. Carrying the sentinel as the user message wires
        // the base contract's errorContextTriggersSessionError assertion
        // without giving up the canned-SSE happy path the other assertions
        // depend on.
        return new AgentExecutionContext(
                CONTRACT_ERROR_SENTINEL, "You are helpful", "claude-opus-4-7",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
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
                AiCapability.MULTI_MODAL,
                AiCapability.CANCELLATION);
    }

    @Test
    void runtimeNameIsAnthropic() {
        assertEquals("anthropic", createRuntime().name());
    }

    @Test
    void runtimePriorityMatchesFrameworkConvention() {
        // Framework runtimes (LC4j, Spring AI, ADK, Koog, Embabel, Semantic
        // Kernel) all use 100 so the resolver picks any of them over the
        // built-in OAI-compat fallback at priority 0. Anthropic follows the
        // same convention.
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
            // Per-invocation answer: inspect the outgoing request body for the
            // contract error sentinel. When present, return a 500 with an
            // error payload so the runtime's session.error(...) path fires
            // (errorContextTriggersSessionError). Otherwise return the canned
            // 200/SSE body the happy-path assertions consume.
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(inv -> {
                        HttpRequest req = inv.getArgument(0);
                        var requestBody = readBody(req);
                        var response = mock(HttpResponse.class);
                        if (requestBody.contains(CONTRACT_ERROR_SENTINEL)) {
                            when(response.statusCode()).thenReturn(500);
                            when(response.body()).thenReturn(new ByteArrayInputStream(
                                    "{\"error\":\"forced contract error\"}"
                                            .getBytes(StandardCharsets.UTF_8)));
                        } else {
                            when(response.statusCode()).thenReturn(statusCode);
                            when(response.body()).thenReturn(new ByteArrayInputStream(
                                    body.getBytes(StandardCharsets.UTF_8)));
                        }
                        return response;
                    });
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Subscribe to the request's body publisher and accumulate the bytes into
     * a UTF-8 string. Mirrors the wire-level boundary inspection the runtime's
     * actual HttpClient does, so the sentinel detection lives at the same
     * layer as the production error-routing logic.
     */
    private static String readBody(HttpRequest req) {
        var publisher = req.bodyPublisher().orElse(null);
        if (publisher == null) {
            return "";
        }
        var collector = new BodyCollector();
        publisher.subscribe(collector);
        return collector.toString();
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }
        @Override
        public void onNext(ByteBuffer item) {
            var copy = new byte[item.remaining()];
            item.get(copy);
            out.write(copy, 0, copy.length);
        }
        @Override
        public void onError(Throwable throwable) {
            // Body capture is best-effort for contract testing; downstream
            // sentinel match falls through to the happy path when capture
            // partially fails — preferable to crashing the test.
        }
        @Override
        public void onComplete() {
            // No-op — toString() reads whatever has been buffered.
        }
        @Override
        public String toString() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Test subclass that injects the mocked client directly. */
    static class TestableAnthropicRuntime extends AnthropicAgentRuntime {
        TestableAnthropicRuntime(AnthropicMessagesClient client) {
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
