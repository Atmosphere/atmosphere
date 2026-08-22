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
 * Regression (registre#16): the package-info tells users to choose this
 * runtime so citations render without lossy translation, while
 * {@code citation-start} was handled with an explicit no-op. Citations
 * must stream as {@link AiEvent.Citation}.
 */
@SuppressWarnings("unchecked")
class CohereCitationStreamTest {

    private static final String CITED_RESPONSE = """
            data: {"type":"message-start","id":"msg_1"}

            data: {"type":"content-start","index":0}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"Grounded answer"}}}}

            data: {"type":"citation-start","index":0,"delta":{"message":{"citations":{"start":0,"end":8,"text":"Grounded","sources":[{"id":"doc-1"},{"id":"doc-2"}]}}}}

            data: {"type":"citation-end","index":0}

            data: {"type":"content-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":4,"output_tokens":2}}}}

            """;

    private static final class EventCollectingSession implements StreamingSession {
        final List<AiEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);

        @Override public String sessionId() { return "citation-test"; }
        @Override public void send(String chunk) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { done.countDown(); }
        @Override public void complete(String summary) { done.countDown(); }
        @Override public void error(Throwable t) { done.countDown(); }
        @Override public boolean isClosed() { return done.getCount() == 0; }
        @Override public void emit(AiEvent event) { events.add(event); }
    }

    @Test
    void citationFramesStreamAsCitationEvents() throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                CITED_RESPONSE.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new EventCollectingSession();

        client.stream("command-a-plus-05-2026", List.of(), "You are helpful",
                "Hi", new AgentExecutionContext(
                        "Hi", "You are helpful", "command-a-plus-05-2026",
                        null, "session-1", "user-1", "conv-1",
                        List.of(), null, null, List.of(), Map.of(),
                        List.of(), null, null), session, null);
        assertTrue(session.done.await(5, TimeUnit.SECONDS));

        var citations = session.events.stream()
                .filter(e -> e instanceof AiEvent.Citation)
                .map(e -> (AiEvent.Citation) e)
                .toList();
        assertEquals(1, citations.size(),
                "citation-start must stream as a Citation event: " + session.events);
        var citation = citations.get(0);
        assertEquals("Grounded", citation.text());
        assertEquals(0, citation.start());
        assertEquals(8, citation.end());
        assertEquals(List.of("doc-1", "doc-2"), citation.sources());
    }
}
