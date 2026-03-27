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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputCapturingSessionTest {

    record MovieReview(String title, int rating, String summary) {}

    private RecordingSession delegate;
    private StructuredOutputParser parser;
    private StructuredOutputCapturingSession session;

    @BeforeEach
    void setUp() {
        delegate = new RecordingSession();
        parser = new JacksonStructuredOutputParser();
        session = new StructuredOutputCapturingSession(delegate, parser, MovieReview.class);
    }

    @Test
    void sendForwardsTextToDelegate() {
        session.send("Hello");
        assertEquals(1, delegate.sends.size());
        assertEquals("Hello", delegate.sends.getFirst());
    }

    @Test
    void completeEmitsEntityCompleteOnValidJson() {
        session.send("{\"title\": \"Inception\", \"rating\": 9, \"summary\": \"Mind-bending\"}");
        session.complete();

        assertTrue(delegate.completed);
        var entityCompleteEvents = delegate.events.stream()
                .filter(e -> e instanceof AiEvent.EntityComplete)
                .toList();
        assertEquals(1, entityCompleteEvents.size());

        var entity = (AiEvent.EntityComplete) entityCompleteEvents.getFirst();
        assertEquals("MovieReview", entity.typeName());
        assertTrue(entity.entity() instanceof MovieReview);
        var review = (MovieReview) entity.entity();
        assertEquals("Inception", review.title());
        assertEquals(9, review.rating());
    }

    @Test
    void completeEmitsEntityStartBeforeEntityComplete() {
        session.send("{\"title\": \"Inception\", \"rating\": 9, \"summary\": \"Great\"}");
        session.complete();

        var entityEvents = delegate.events.stream()
                .filter(e -> e instanceof AiEvent.EntityStart || e instanceof AiEvent.EntityComplete)
                .toList();
        assertTrue(entityEvents.size() >= 2);
        assertTrue(entityEvents.getFirst() instanceof AiEvent.EntityStart);
        assertTrue(entityEvents.getLast() instanceof AiEvent.EntityComplete);
    }

    @Test
    void errorDoesNotEmitEntityEvents() {
        session.send("{\"title\": \"Bad\"");
        session.error(new RuntimeException("LLM error"));

        assertTrue(delegate.errored);
        var entityEvents = delegate.events.stream()
                .filter(e -> e instanceof AiEvent.EntityComplete)
                .toList();
        assertTrue(entityEvents.isEmpty());
    }

    @Test
    void progressDelegates() {
        session.progress("Thinking...");
        assertEquals(1, delegate.progresses.size());
    }

    @Test
    void sessionIdDelegates() {
        assertEquals("test-session", session.sessionId());
    }

    @Test
    void isClosedDelegates() {
        assertFalse(session.isClosed());
    }

    @Test
    void completeWithSummaryEmitsEntity() {
        session.send("{\"title\": \"Inception\", \"rating\": 9, \"summary\": \"Great\"}");
        session.complete("Done");

        assertTrue(delegate.completed);
        var entityCompleteEvents = delegate.events.stream()
                .filter(e -> e instanceof AiEvent.EntityComplete)
                .toList();
        assertEquals(1, entityCompleteEvents.size());
    }

    @Test
    void handlesMarkdownFencedJson() {
        session.send("```json\n{\"title\": \"Inception\", \"rating\": 9, \"summary\": \"Great\"}\n```");
        session.complete();

        var entityCompleteEvents = delegate.events.stream()
                .filter(e -> e instanceof AiEvent.EntityComplete)
                .toList();
        assertEquals(1, entityCompleteEvents.size());
    }

    @Test
    void parseFailureDoesNotPreventComplete() {
        session.send("This is not JSON at all");
        session.complete();

        assertTrue(delegate.completed);
        // EntityComplete should not be emitted for unparseable output
        var entityCompleteEvents = delegate.events.stream()
                .filter(e -> e instanceof AiEvent.EntityComplete)
                .toList();
        assertTrue(entityCompleteEvents.isEmpty());
    }

    /**
     * Recording delegate that captures all calls.
     */
    static class RecordingSession implements StreamingSession {
        final List<String> sends = new ArrayList<>();
        final List<AiEvent> events = new ArrayList<>();
        final List<String> progresses = new ArrayList<>();
        boolean completed;
        boolean errored;
        String completeSummary;

        @Override
        public String sessionId() { return "test-session"; }

        @Override
        public void send(String text) { sends.add(text); }

        @Override
        public void emit(AiEvent event) { events.add(event); }

        @Override
        public void sendMetadata(String key, Object value) {}

        @Override
        public void progress(String message) { progresses.add(message); }

        @Override
        public void complete() { completed = true; }

        @Override
        public void complete(String summary) {
            completed = true;
            completeSummary = summary;
        }

        @Override
        public void error(Throwable t) { errored = true; }

        @Override
        public boolean isClosed() { return false; }
    }
}
