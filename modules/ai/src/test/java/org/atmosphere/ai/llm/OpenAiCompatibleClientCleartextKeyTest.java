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

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.CollectingSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The built-in client sends its API key over plain {@code http} only to a
 * loopback host. Before, any {@code http} base URL got the key in an
 * {@code Authorization} header, so a key configured with
 * {@code LLM_BASE_URL=http://gpu-box:8000/v1} crossed the network in
 * cleartext. A host under {@code .invalid} never resolves: nothing here
 * depends on DNS.
 */
class OpenAiCompatibleClientCleartextKeyTest {

    private static final String REMOTE_HTTP = "http://llm.invalid:8000/v1";
    private static final String KEY = "sk-test-not-a-real-key";

    @SuppressWarnings("unchecked")
    private static HttpClient recordingClient() throws IOException, InterruptedException {
        var http = mock(HttpClient.class);
        // Any answer will do: the tests only look at what was sent.
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("not a real server"));
        return http;
    }

    private static OpenAiCompatibleClient.Builder client(HttpClient http, String baseUrl) {
        return OpenAiCompatibleClient.builder()
                .baseUrl(baseUrl)
                .apiKey(KEY)
                .httpClient(http)
                .retryPolicy(org.atmosphere.ai.RetryPolicy.NONE)
                .timeout(Duration.ofSeconds(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyOverPlainHttpToANonLoopbackHostIsNeverSentAndTheTurnEndsWithTheReason() throws Exception {
        var http = recordingClient();
        var session = new CollectingSession("cleartext");

        client(http, REMOTE_HTTP).build()
                .streamChatCompletion(ChatCompletionRequest.of("gpt-4o-mini", "hello"), session);

        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertTrue(session.failed(), "the turn ends with an error");
        var reason = assertInstanceOf(IllegalStateException.class, session.failure()).getMessage();
        assertTrue(reason.contains(REMOTE_HTTP), reason);
        assertTrue(reason.contains("cleartext"), reason);
        assertTrue(reason.contains(CleartextApiKeyPolicy.ALLOW_PROPERTY), reason);
        assertFalse(reason.contains(KEY), "the reason never carries the key");
    }

    /**
     * A refused turn is logged once: the client's ERROR naming the refusal. The
     * streaming session that ends the turn used to add a second ERROR with the
     * refusal's full stack trace, which named nothing the message did not.
     */
    @Test
    void aRefusedTurnLogsOneErrorWithoutAStackAndStillSendsTheErrorFrame() throws Exception {
        var client = client(recordingClient(), REMOTE_HTTP).build();
        var resource = mock(org.atmosphere.cpr.AtmosphereResource.class);
        var broadcaster = mock(org.atmosphere.cpr.Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        var frames = new java.util.ArrayList<String>();
        when(broadcaster.broadcast(any(), org.mockito.ArgumentMatchers.anySet())).thenAnswer(inv -> {
            frames.add(String.valueOf(((org.atmosphere.cpr.RawMessage) inv.getArgument(0)).message()));
            return null;
        });
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var loggers = List.of(
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleClient.class),
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        org.atmosphere.ai.DefaultStreamingSession.class));
        appender.start();
        loggers.forEach(l -> l.addAppender(appender));
        try {
            var session = org.atmosphere.ai.StreamingSessions.start("refused-" + System.nanoTime(), resource);
            client.streamChatCompletion(ChatCompletionRequest.of("gpt-4o-mini", "hello"), session);
            assertTrue(session.hasErrored(), "the refused turn still ends in error");
        } finally {
            loggers.forEach(l -> l.detachAppender(appender));
            appender.stop();
        }

        var errors = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR).toList();
        assertEquals(1, errors.size(), "one ERROR for one refused turn: " + errors);
        assertTrue(errors.get(0).getFormattedMessage().contains(REMOTE_HTTP), errors.get(0).getFormattedMessage());
        assertTrue(appender.list.stream().allMatch(e -> e.getThrowableProxy() == null),
                "the refusal was logged with a stack trace: " + appender.list);
        assertEquals(1, frames.size(), "one terminal frame: " + frames);
        var frame = org.atmosphere.ai.filter.AiStreamMessage.parse(frames.get(0));
        assertTrue(frame.isError(), frames.get(0));
        assertTrue(frame.data().contains(REMOTE_HTTP), frame.data());
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelEnumerationIsRefusedTooWithoutSendingAnything() throws Exception {
        var http = recordingClient();

        assertEquals(List.of(), client(http, REMOTE_HTTP).build().listModels());

        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withTheOptInTheKeyIsSent() throws Exception {
        var http = recordingClient();
        var session = new CollectingSession("opt-in");

        client(http, REMOTE_HTTP).allowCleartextApiKey(true).build()
                .streamChatCompletion(ChatCompletionRequest.of("gpt-4o-mini", "hello"), session);

        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("Bearer " + KEY, sent.getValue().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyToALoopbackHostOrOverHttpsIsSent() throws Exception {
        for (var endpoint : List.of("http://localhost:11434/v1", "http://127.0.0.1:8000/v1",
                "http://[::1]:8000/v1", "https://llm.invalid/v1")) {
            var http = recordingClient();
            client(http, endpoint).build()
                    .streamChatCompletion(ChatCompletionRequest.of("gpt-4o-mini", "hello"),
                            new CollectingSession(endpoint));
            verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeylessCallGoesToAnyHost() throws Exception {
        var http = recordingClient();
        OpenAiCompatibleClient.builder().baseUrl(REMOTE_HTTP).httpClient(http)
                .retryPolicy(org.atmosphere.ai.RetryPolicy.NONE).build()
                .streamChatCompletion(ChatCompletionRequest.of("llama3.2", "hello"),
                        new CollectingSession("keyless"));
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void theApplicationsOptInReachesTheClientItsSettingsBuild() {
        try (var refusing = AiEnvironment.create(property -> null);
             var optedIn = AiEnvironment.create(property ->
                     Map.of(CleartextApiKeyPolicy.ALLOW_PROPERTY, "true").get(property))) {
            var refused = (OpenAiCompatibleClient) refusing
                    .configure("remote", "gpt-4o-mini", KEY, REMOTE_HTTP).client();
            var allowed = (OpenAiCompatibleClient) optedIn
                    .configure("remote", "gpt-4o-mini", KEY, REMOTE_HTTP).client();

            assertFalse(refused.allowsCleartextApiKey());
            assertTrue(allowed.allowsCleartextApiKey());
            assertFalse(refusing.resolveAllowCleartextApiKey());
            assertTrue(optedIn.resolveAllowCleartextApiKey());
        }
    }

    @Test
    void theBuiltInEmbeddingRuntimeRefusesTooAndTheOptInLetsTheRequestGo() {
        try (var refusing = AiEnvironment.create(property -> null);
             var optedIn = AiEnvironment.create(property ->
                     Map.of(CleartextApiKeyPolicy.ALLOW_PROPERTY, "true").get(property))) {
            refusing.configure("remote", "gpt-4o-mini", KEY, REMOTE_HTTP);
            optedIn.configure("remote", "gpt-4o-mini", KEY, REMOTE_HTTP);

            var refused = new BuiltInEmbeddingRuntime();
            refused.attach(refusing);
            var reason = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> refused.embed("hello")).getMessage();
            assertTrue(reason.contains(REMOTE_HTTP) && reason.contains("cleartext"), reason);

            // With the opt-in the request is sent: it fails only because the
            // .invalid host does not exist.
            var allowed = new BuiltInEmbeddingRuntime();
            allowed.attach(optedIn);
            var sent = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> allowed.embed("hello"));
            assertTrue(sent.getMessage().startsWith("Embedding request failed"), sent.getMessage());
            allowed.onEnvironmentClose();
        }
    }
}
