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
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression (registre#16): the package-info sells the runtime on extended
 * thinking, but {@code thinking} blocks and {@code thinking_delta} /
 * {@code signature_delta} frames were dropped at the switch defaults — no
 * reasoning ever reached a client. Thinking must stream as
 * {@link AiEvent.ReasoningDelta} / {@link AiEvent.ReasoningComplete}.
 */
@SuppressWarnings("unchecked")
class AnthropicReasoningStreamTest {

    private static final String THINKING_RESPONSE = """
            data: {"type":"message_start","message":{"id":"msg_r1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think about this."}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"c2lnbmF0dXJl"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"The answer."}}

            data: {"type":"content_block_stop","index":1}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":5,"output_tokens":2}}

            data: {"type":"message_stop"}

            """;

    private static final class EventCollectingSession implements StreamingSession {
        final List<AiEvent> events = new CopyOnWriteArrayList<>();
        final StringBuilder text = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);

        @Override public String sessionId() { return "reasoning-test"; }
        @Override public void send(String chunk) { text.append(chunk); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { done.countDown(); }
        @Override public void complete(String summary) { done.countDown(); }
        @Override public void error(Throwable t) { done.countDown(); }
        @Override public boolean isClosed() { return done.getCount() == 0; }
        @Override public void emit(AiEvent event) { events.add(event); }
    }

    @Test
    void thinkingBlocksStreamAsReasoningEvents() throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                THINKING_RESPONSE.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new EventCollectingSession();

        client.stream("claude-sonnet-4-6", List.of(), "You are helpful",
                "Hi", new AgentExecutionContext(
                        "Hi", "You are helpful", "claude-sonnet-4-6",
                        null, "session-1", "user-1", "conv-1",
                        List.of(), null, null, List.of(), Map.of(),
                        List.of(), null, null), session, null);
        assertTrue(session.done.await(5, TimeUnit.SECONDS));

        var deltas = session.events.stream()
                .filter(e -> e instanceof AiEvent.ReasoningDelta)
                .map(e -> (AiEvent.ReasoningDelta) e)
                .toList();
        assertEquals(1, deltas.size(),
                "thinking_delta frames must stream as reasoning: " + session.events);
        assertEquals("Let me think about this.", deltas.get(0).text());
        var completes = session.events.stream()
                .filter(e -> e instanceof AiEvent.ReasoningComplete)
                .map(e -> (AiEvent.ReasoningComplete) e)
                .toList();
        assertEquals(1, completes.size(),
                "the closing thinking block must emit ReasoningComplete: "
                        + session.events);
        assertEquals("c2lnbmF0dXJl", completes.get(0).signature(),
                "the accumulated signature rides the terminal event");
        assertEquals("The answer.", session.text.toString(),
                "the answer text still streams normally");
    }
}
