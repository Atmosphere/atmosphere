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

import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Token usage of streamed Chat Completions turns: the request asks for it
 * ({@code stream_options.include_usage}), a usage chunk with an empty
 * {@code choices} array (OpenAI, Ollama) reaches {@link StreamingSession#usage},
 * and a usage repeated on every chunk (Gemini) is reported once per round.
 */
class OpenAiCompatibleClientStreamUsageTest {

    @Test
    void streamedRequestAsksForUsage() throws Exception {
        for (var baseUrl : List.of(AiConfig.OLLAMA_ENDPOINT, AiConfig.GEMINI_ENDPOINT,
                AiConfig.OPENAI_ENDPOINT)) {
            var client = OpenAiCompatibleClient.builder().baseUrl(baseUrl).apiKey("k").build();
            Method m = OpenAiCompatibleClient.class.getDeclaredMethod(
                    "buildRequestBody", ChatCompletionRequest.class);
            m.setAccessible(true);
            var body = new ObjectMapper().readTree(
                    (String) m.invoke(client, ChatCompletionRequest.of("m", "Hi")));
            assertTrue(body.get("stream").asBoolean(), baseUrl);
            assertTrue(body.path("stream_options").path("include_usage").asBoolean(),
                    "streamed request to " + baseUrl + " must carry stream_options.include_usage");
        }
    }

    @Test
    void usageChunkWithEmptyChoicesReachesSessionAndModelEnd() throws Exception {
        // The exact shape Ollama (and OpenAI) stream with include_usage: the
        // finish_reason chunk carries no usage; a last chunk has choices: [].
        var sse = """
                data: {"id":"c1","model":"qwen2.5:3b","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"c1","model":"qwen2.5:3b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"c1","model":"qwen2.5:3b","choices":[],"usage":{"prompt_tokens":31,"prompt_tokens_details":{"cached_tokens":4},"completion_tokens":5,"total_tokens":36}}

                data: [DONE]

                """;
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(AiConfig.OLLAMA_ENDPOINT)
                .httpClient(httpClient(sse))
                .build();
        var modelEndUsage = new CopyOnWriteArrayList<TokenUsage>();
        var listener = new AgentLifecycleListener() {
            @Override
            public void onModelEnd(String model, TokenUsage usage, long durationMillis) {
                modelEndUsage.add(usage);
            }
        };
        var session = new RecordingSession();

        client.streamChatCompletion(ChatCompletionRequest.builder("qwen2.5:3b")
                .user("Hi").listeners(List.of(listener)).build(), session);

        assertEquals("Hi", session.text.toString());
        assertEquals(1, session.usages.size(), "usage reported once: " + session.usages);
        var usage = session.usages.get(0);
        assertEquals(31L, usage.input());
        assertEquals(5L, usage.output());
        assertEquals(4L, usage.cachedInput());
        assertEquals(36L, usage.total());
        assertEquals(List.of(usage), modelEndUsage, "onModelEnd carries the same usage");
        assertTrue(session.completed.get());
        assertEquals(List.of(), session.errors);
    }

    @Test
    void usageRepeatedOnEveryChunkIsReportedOnceWithTheLastCounts() throws Exception {
        // Gemini's OpenAI-compatible endpoint, with include_usage, puts a
        // cumulative usage on every chunk.
        var sse = """
                data: {"choices":[{"delta":{"content":"one","role":"assistant"},"index":0}],"model":"gemini-2.5-flash","usage":{"completion_tokens":43,"prompt_tokens":14,"total_tokens":278}}

                data: {"choices":[{"delta":{"content":" two","role":"assistant"},"index":0}],"model":"gemini-2.5-flash","usage":{"completion_tokens":91,"prompt_tokens":14,"total_tokens":326}}

                data: {"choices":[{"delta":{"content":" three","role":"assistant"},"finish_reason":"stop","index":0}],"model":"gemini-2.5-flash","usage":{"completion_tokens":191,"prompt_tokens":14,"total_tokens":426}}

                data: [DONE]

                """;
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(AiConfig.GEMINI_ENDPOINT)
                .apiKey("k")
                .httpClient(httpClient(sse))
                .build();
        var session = new RecordingSession();

        client.streamChatCompletion(ChatCompletionRequest.of("gemini-2.5-flash", "Count"), session);

        assertEquals("one two three", session.text.toString());
        assertEquals(1, session.usages.size(),
                "a cumulative usage on every chunk must be reported once: " + session.usages);
        assertEquals(14L, session.usages.get(0).input());
        assertEquals(191L, session.usages.get(0).output());
        assertEquals(426L, session.usages.get(0).total());
    }

    @Test
    void eachToolLoopRoundReportsItsOwnUsageOnce() throws Exception {
        var toolRound = """
                data: {"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Montreal\\"}"}}]},"finish_reason":null}]}

                data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"id":"c1","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":7,"total_tokens":27}}

                data: [DONE]

                """;
        var textRound = """
                data: {"id":"c2","choices":[{"index":0,"delta":{"content":"22C"},"finish_reason":null}]}

                data: {"id":"c2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"c2","choices":[],"usage":{"prompt_tokens":40,"completion_tokens":3,"total_tokens":43}}

                data: [DONE]

                """;
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(AiConfig.OLLAMA_ENDPOINT)
                .httpClient(httpClient(toolRound, textRound))
                .build();
        var tool = ToolDefinition.builder("get_weather", "Get weather for a city")
                .parameter("city", "The city name", "string")
                .executor(args -> "22C in " + args.get("city"))
                .build();
        var session = new RecordingSession();

        client.streamChatCompletion(ChatCompletionRequest.builder("m")
                .user("Weather?").tools(List.of(tool)).build(), session);

        assertEquals("22C", session.text.toString());
        assertEquals(2, session.usages.size(), "one usage per round: " + session.usages);
        assertEquals(27L, session.usages.get(0).total());
        assertEquals(43L, session.usages.get(1).total());
    }

    /** Gemini-shaped chunks with a cumulative usage, before the stream breaks. */
    private static final String PARTIAL_GEMINI_STREAM = """
            data: {"choices":[{"delta":{"content":"one","role":"assistant"},"index":0}],"model":"gemini-2.5-flash","usage":{"completion_tokens":43,"prompt_tokens":14,"total_tokens":278}}

            data: {"choices":[{"delta":{"content":" two","role":"assistant"},"index":0}],"model":"gemini-2.5-flash","usage":{"completion_tokens":91,"prompt_tokens":14,"total_tokens":326}}

            """;

    @Test
    void readErrorReportsTheUsageReceivedAndFailsTheModelCall() throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(AiConfig.GEMINI_ENDPOINT)
                .apiKey("k")
                .httpClient(httpClient(new BreakingStream(PARTIAL_GEMINI_STREAM, null)))
                .build();
        var listener = new RecordingListener();
        var session = new RecordingSession();

        client.streamChatCompletion(ChatCompletionRequest.builder("gemini-2.5-flash")
                .user("Count").listeners(List.of(listener)).build(), session);

        assertEquals(1, session.errors.size(), "the read error ends the turn: " + session.errors);
        assertEquals(1, session.usages.size(), "usage received before the error: " + session.usages);
        assertEquals(91L, session.usages.get(0).output());
        assertEquals(326L, session.usages.get(0).total());
        assertEquals(1, listener.errors.size(), "the model call is failed, not left open");
        assertEquals(List.of(), listener.ends);
    }

    @Test
    void cancelThatClosesTheStreamReportsTheUsageReceivedAndEndsTheModelCall() throws Exception {
        var cancelled = new AtomicBoolean();
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(AiConfig.GEMINI_ENDPOINT)
                .apiKey("k")
                // The caller's cancel closes the in-flight stream: the flag is
                // set and the blocked read throws.
                .httpClient(httpClient(new BreakingStream(PARTIAL_GEMINI_STREAM, cancelled)))
                .build();
        var listener = new RecordingListener();
        var session = new RecordingSession();

        client.streamChatCompletion(ChatCompletionRequest.builder("gemini-2.5-flash")
                .user("Count").listeners(List.of(listener)).build(), session, cancelled, stream -> { });

        assertTrue(session.completed.get(), "a cancel completes the turn");
        assertEquals(List.of(), session.errors);
        assertEquals(1, session.usages.size(), "usage received before the cancel: " + session.usages);
        assertEquals(326L, session.usages.get(0).total());
        assertEquals(List.of(session.usages.get(0)), listener.ends,
                "the model call ends with the usage received");
        assertEquals(List.of(), listener.errors);
    }

    private static final class RecordingListener implements AgentLifecycleListener {
        final List<TokenUsage> ends = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onModelEnd(String model, TokenUsage usage, long durationMillis) {
            ends.add(usage);
        }

        @Override
        public void onModelError(String model, Throwable error) {
            errors.add(error);
        }
    }

    /**
     * Serves {@code content}, then fails the next read, as a dropped
     * connection does. With a {@code cancelled} flag it first sets it, as a
     * caller's cancel that closes the stream does.
     */
    private static final class BreakingStream extends java.io.InputStream {
        private final ByteArrayInputStream content;
        private final AtomicBoolean cancelled;

        BreakingStream(String content, AtomicBoolean cancelled) {
            this.content = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            this.cancelled = cancelled;
        }

        @Override
        public int read() throws java.io.IOException {
            var b = content.read();
            return b >= 0 ? b : fail();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            var n = content.read(buffer, offset, length);
            return n >= 0 ? n : fail();
        }

        private int fail() throws java.io.IOException {
            if (cancelled != null) {
                cancelled.set(true);
                throw new java.io.IOException("Stream closed");
            }
            throw new java.io.IOException("Connection reset");
        }
    }

    private static final class RecordingSession implements StreamingSession {
        final StringBuilder text = new StringBuilder();
        final List<TokenUsage> usages = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override public String sessionId() { return "stream-usage"; }
        @Override public void send(String t) { text.append(t); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void usage(TokenUsage usage) { usages.add(usage); }
        @Override public void complete() { completed.set(true); }
        @Override public void complete(String summary) { completed.set(true); }
        @Override public void error(Throwable t) { errors.add(t); }
        @Override public boolean isClosed() { return completed.get() || !errors.isEmpty(); }
    }

    private static HttpClient httpClient(String... bodies) throws Exception {
        var streams = new java.io.InputStream[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            streams[i] = new ByteArrayInputStream(bodies[i].getBytes(StandardCharsets.UTF_8));
        }
        return httpClient(streams);
    }

    // Mockito matches the generic HttpClient.send signature only through the
    // raw BodyHandler class, and a generic HttpResponse array cannot be created.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient httpClient(java.io.InputStream... bodies) throws Exception {
        var httpClient = mock(HttpClient.class);
        HttpResponse<java.io.InputStream>[] responses = new HttpResponse[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
            doReturn(200).when(response).statusCode();
            doReturn(bodies[i]).when(response).body();
            doReturn(HttpHeaders.of(Map.of(), (a, b) -> true)).when(response).headers();
            responses[i] = response;
        }
        doReturn(responses[0], (Object[]) Arrays.copyOfRange(responses, 1, responses.length))
                .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return httpClient;
    }
}
