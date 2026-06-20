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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.GenerationParams;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.AgentExecutionContext;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Anthropic Runtime-Truth proof (Correctness Invariant #5) for the framework
 * {@link GenerationParams}:
 * <ul>
 *   <li>{@code maxTokens} precedence — {@code anthropic.max.tokens} sysprop
 *       still wins; {@code GenerationParams.maxTokens()} fills the gap when the
 *       sysprop is unset.</li>
 *   <li>{@code temperature}/{@code topP}/{@code stop} reach the Messages body
 *       as {@code temperature}/{@code top_p}/{@code stop_sequences} when set,
 *       and are omitted (byte-identical to today) when unset.</li>
 * </ul>
 */
class AnthropicGenerationParamsTest {

    private static final String TEXT_RESPONSE = """
            data: {"type":"message_start","message":{"id":"msg_1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":1,"output_tokens":1}}

            data: {"type":"message_stop"}

            """;

    @AfterEach
    public void tearDown() {
        System.clearProperty("anthropic.max.tokens");
        // Reset AiConfig singleton so generation state does not leak.
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    void temperatureTopPStopReachTheBodyWhenSet() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .generation(new GenerationParams(0.33, 444, 0.55, List.of("STOP", "HALT")))
                .build();
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), "be terse",
                "ping", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));

        var body = capturedBody(httpClient);
        assertTrue(body.contains("\"temperature\":0.33"), body);
        assertTrue(body.contains("\"top_p\":0.55"), body);
        assertTrue(body.contains("\"stop_sequences\":[\"STOP\",\"HALT\"]"), body);
    }

    @Test
    void unsetGenerationOmitsTemperatureTopPStop() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .generation(GenerationParams.defaults())
                .build();
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), "be terse",
                "ping", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));

        var body = capturedBody(httpClient);
        assertFalse(body.contains("\"temperature\""), body);
        assertFalse(body.contains("\"top_p\""), body);
        assertFalse(body.contains("\"stop_sequences\""), body);
        // max_tokens is always present (a required Anthropic field) — unchanged.
        assertTrue(body.contains("\"max_tokens\""), body);
    }

    @Test
    void generationMaxTokensUsedWhenSyspropUnset() {
        // Drives the REAL AnthropicAgentRuntime.createNativeClient so the test
        // exercises production precedence, not a re-implementation.
        System.clearProperty("anthropic.max.tokens");
        installGeneration(new GenerationParams(null, 1234, null, null));
        var client = new TestableRuntime().create(AiConfig.get());
        assertEquals(1234, client.maxTokensForTest(),
                "GenerationParams.maxTokens must fill max_tokens when the sysprop is unset");
        assertEquals(1234, client.generationForTest().maxTokens(),
                "the generation override must also ride on the client");
    }

    @Test
    void syspropMaxTokensWinsOverGeneration() {
        System.setProperty("anthropic.max.tokens", "777");
        installGeneration(new GenerationParams(null, 1234, null, null));
        var client = new TestableRuntime().create(AiConfig.get());
        assertEquals(777, client.maxTokensForTest(),
                "anthropic.max.tokens sysprop must win over GenerationParams.maxTokens");
    }

    @Test
    void defaultMaxTokensWhenNeitherSyspropNorGeneration() {
        System.clearProperty("anthropic.max.tokens");
        installGeneration(GenerationParams.defaults());
        var client = new TestableRuntime().create(AiConfig.get());
        assertEquals(4096, client.maxTokensForTest(),
                "client default max_tokens when neither sysprop nor generation is set");
    }

    // -- helpers --

    private static void installGeneration(GenerationParams generation) {
        var settings = AiConfig.configure("remote", "claude-sonnet-4-6", "test-key", null);
        try {
            var f = AiConfig.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, new AiConfig.LlmSettings(
                    settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                    settings.apiKey(), settings.promptCacheKeyMode(), generation));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not install AiConfig settings", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String capturedBody(HttpClient httpClient) throws Exception {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return drainBody(captor.getValue().bodyPublisher().orElseThrow());
    }

    private static String drainBody(HttpRequest.BodyPublisher publisher) {
        var collector = new java.util.concurrent.atomic.AtomicReference<String>();
        var done = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private final StringBuilder buf = new StringBuilder();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                buf.append(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                collector.set(buf.toString());
                done.countDown();
            }
        });
        try {
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        var body = collector.get();
        assertNotNull(body, "request body must drain to a string");
        return body;
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hi", "You are helpful", "claude-sonnet-4-6",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockSingleResponse(int statusCode, String body) {
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

    /**
     * Exposes the protected {@code createNativeClient} so the wiring tests can
     * drive the REAL max_tokens precedence + generation threading the runtime
     * resolves — not a re-implementation.
     */
    static final class TestableRuntime extends AnthropicAgentRuntime {
        AnthropicMessagesClient create(AiConfig.LlmSettings settings) {
            return createNativeClient(settings);
        }
    }
}
