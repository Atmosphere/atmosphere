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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.GenerationParams;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Runtime-Truth proof (Correctness Invariant #5) that the framework-level
 * {@link GenerationParams} a deployer sets on {@code AiConfig} actually reach
 * the built-in OpenAI-compatible wire request body — and that an empty/default
 * {@link GenerationParams} keeps the body byte-identical to the pre-feature
 * behavior. Covers BOTH the chat-completions and Responses-API body builders
 * (Mode Parity, Correctness Invariant #7).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class OpenAiCompatibleClientGenerationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtmosphereResource resource;
    private Broadcaster broadcaster;

    @BeforeEach
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("r1");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class), any(Set.class))).thenReturn(mock(Future.class));
    }

    @Test
    public void testGenerationParamsReachChatCompletionsBody() throws Exception {
        var httpClient = mockHttpClient(200, "data: [DONE]\n\n");
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .generation(new GenerationParams(0.2, 256, 0.85, List.of("STOP", "END")))
                .build();

        var session = StreamingSessions.start("gen-chat", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test-model", "Hi"), session);

        var body = MAPPER.readTree(capturedBody(httpClient));
        assertEquals(0.2, body.get("temperature").asDouble(), 1e-9,
                "GenerationParams.temperature must override the per-request value");
        assertEquals(256, body.get("max_tokens").asInt(),
                "GenerationParams.maxTokens must reach the chat-completions max_tokens");
        assertEquals(0.85, body.get("top_p").asDouble(), 1e-9,
                "GenerationParams.topP must reach top_p");
        assertTrue(body.has("stop"), "GenerationParams.stop must reach the body");
        var stop = body.get("stop");
        assertTrue(stop.isArray() && stop.size() == 2);
        assertEquals("STOP", stop.get(0).asString());
        assertEquals("END", stop.get(1).asString());
    }

    @Test
    public void testEmptyGenerationIsByteIdenticalChatCompletions() throws Exception {
        // Two clients, identical except one is built WITHOUT any generation
        // builder call (defaults) and one is built WITH GenerationParams.defaults().
        // Both bodies must equal the legacy shape: temperature present (per-request
        // default), no max_tokens for a no-cap request, and NO top_p / stop fields.
        var httpClientA = mockHttpClient(200, "data: [DONE]\n\n");
        var clientA = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClientA)
                .build();
        var sessionA = StreamingSessions.start("gen-empty-a", resource);
        // maxStreamingTexts(0) so no max_tokens is emitted (legacy: only > 0 emits)
        clientA.streamChatCompletion(
                ChatCompletionRequest.builder("test-model").user("Hi")
                        .temperature(0.7).maxStreamingTexts(0).build(),
                sessionA);
        var bodyA = MAPPER.readTree(capturedBody(httpClientA));

        var httpClientB = mockHttpClient(200, "data: [DONE]\n\n");
        var clientB = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClientB)
                .generation(GenerationParams.defaults())
                .build();
        var sessionB = StreamingSessions.start("gen-empty-b", resource);
        clientB.streamChatCompletion(
                ChatCompletionRequest.builder("test-model").user("Hi")
                        .temperature(0.7).maxStreamingTexts(0).build(),
                sessionB);
        var bodyB = MAPPER.readTree(capturedBody(httpClientB));

        // Defaults vs. no-call-at-all must be identical.
        assertEquals(bodyA, bodyB, "GenerationParams.defaults() must be byte-identical to no generation");

        // And both must match the legacy shape exactly.
        assertEquals(0.7, bodyA.get("temperature").asDouble(), 1e-9);
        assertFalse(bodyA.has("max_tokens"), "no max_tokens for a no-cap request (legacy behavior)");
        assertFalse(bodyA.has("top_p"), "top_p must be absent when generation is unset");
        assertFalse(bodyA.has("stop"), "stop must be absent when generation is unset");
    }

    @Test
    public void testGenerationMaxTokensFallsBackToPerRequestWhenUnset() throws Exception {
        // generation.maxTokens unset → legacy fallback to maxStreamingTexts > 0.
        var httpClient = mockHttpClient(200, "data: [DONE]\n\n");
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .generation(GenerationParams.defaults())
                .build();
        var session = StreamingSessions.start("gen-fallback", resource);
        client.streamChatCompletion(
                ChatCompletionRequest.builder("test-model").user("Hi")
                        .maxStreamingTexts(99).build(),
                session);
        var body = MAPPER.readTree(capturedBody(httpClient));
        assertEquals(99, body.get("max_tokens").asInt(),
                "unset generation.maxTokens must preserve the per-request max_tokens path");
    }

    @Test
    public void testGenerationParamsReachResponsesApiBody() throws Exception {
        // The Responses API path (OpenAI endpoint + conversationId) must honor
        // temperature/maxTokens(→max_output_tokens)/topP. The Responses API has
        // no `stop` field, so stop is intentionally NOT emitted there.
        var sseResponse = """
                data: {"type":"response.output_text.delta","delta":"hi"}

                data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-4o"}}

                data: [DONE]

                """;
        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .generation(new GenerationParams(0.4, 128, 0.7, List.of("STOP")))
                .build();
        // Seed a previous response id so the FIRST send uses the Responses API path.
        client.seedResponseId("conv-1", "resp_prev");

        var session = StreamingSessions.start("gen-responses", resource);
        client.streamChatCompletion(
                ChatCompletionRequest.builder("gpt-4o").user("Hi").conversationId("conv-1").build(),
                session);

        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var sent = reqCaptor.getValue();
        assertTrue(sent.uri().getPath().endsWith("/responses"),
                "must dispatch via the Responses API");
        var body = MAPPER.readTree(drainBody(sent));
        assertEquals(0.4, body.get("temperature").asDouble(), 1e-9);
        assertEquals(128, body.get("max_output_tokens").asInt(),
                "Responses API uses max_output_tokens for the maxTokens override");
        assertEquals(0.7, body.get("top_p").asDouble(), 1e-9);
        assertFalse(body.has("stop"),
                "Responses API has no stop parameter — stop must NOT be emitted");
        assertFalse(body.has("stop_sequences"));
    }

    @Test
    public void testEmptyGenerationByteIdenticalResponsesApi() throws Exception {
        var sseResponse = """
                data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-4o"}}

                data: [DONE]

                """;
        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        client.seedResponseId("conv-2", "resp_prev");
        var session = StreamingSessions.start("gen-responses-empty", resource);
        client.streamChatCompletion(
                ChatCompletionRequest.builder("gpt-4o").user("Hi")
                        .temperature(0.5).maxStreamingTexts(0).conversationId("conv-2").build(),
                session);

        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var body = MAPPER.readTree(drainBody(reqCaptor.getValue()));
        // Legacy Responses shape: temperature present (>= 0), no max_output_tokens
        // for a no-cap request, no top_p.
        assertEquals(0.5, body.get("temperature").asDouble(), 1e-9);
        assertFalse(body.has("max_output_tokens"),
                "no max_output_tokens for a no-cap request (legacy behavior)");
        assertFalse(body.has("top_p"), "top_p absent when generation unset");
    }

    // -- helpers --

    private static String capturedBody(HttpClient httpClient) throws Exception {
        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        return drainBody(reqCaptor.getValue());
    }

    /** Synchronously drain a captured request's BodyPublisher into a UTF-8 string. */
    private static String drainBody(HttpRequest request) {
        var publisher = request.bodyPublisher().orElseThrow(
                () -> new AssertionError("request has no body publisher"));
        var collected = new java.io.ByteArrayOutputStream();
        var latch = new CompletableFuture<Void>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                collected.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                latch.complete(null);
            }
        });
        latch.join();
        return collected.toString(StandardCharsets.UTF_8);
    }

    private HttpClient mockHttpClient(int statusCode, String body) throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }
}
