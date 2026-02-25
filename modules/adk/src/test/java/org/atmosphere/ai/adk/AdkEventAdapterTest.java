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
package org.atmosphere.ai.adk;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdkEventAdapterTest {

    private StreamingSession mockSession;

    @BeforeEach
    void setUp() {
        mockSession = mock(StreamingSession.class);
        when(mockSession.isClosed()).thenReturn(false);
    }

    @Test
    void partialEventsAreSentAsTokens() throws Exception {
        var event = createPartialEvent("Hello");
        var latch = new CountDownLatch(1);

        doAnswer(inv -> { latch.countDown(); return null; })
                .when(mockSession).send("Hello");

        AdkEventAdapter.bridge(Flowable.just(event), mockSession);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        verify(mockSession).send("Hello");
    }

    @Test
    void multiplePartialEventsStreamTokens() throws Exception {
        var e1 = createPartialEvent("Hello");
        var e2 = createPartialEvent(" world");
        var latch = new CountDownLatch(1);

        doAnswer(inv -> { latch.countDown(); return null; })
                .when(mockSession).complete();

        var events = Flowable.just(e1, e2, createTurnCompleteEvent(null));
        AdkEventAdapter.bridge(events, mockSession);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(mockSession, times(2)).send(captor.capture());
        assertEquals(List.of("Hello", " world"), captor.getAllValues());
        verify(mockSession).complete();
    }

    @Test
    void turnCompleteWithSummaryCallsCompleteWithSummary() throws Exception {
        var event = createTurnCompleteEvent("Final answer");
        var latch = new CountDownLatch(1);

        doAnswer(inv -> { latch.countDown(); return null; })
                .when(mockSession).complete("Final answer");

        AdkEventAdapter.bridge(Flowable.just(event), mockSession);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        verify(mockSession).complete("Final answer");
    }

    @Test
    void turnCompleteWithoutTextCallsComplete() throws Exception {
        var event = createTurnCompleteEvent(null);
        var latch = new CountDownLatch(1);

        doAnswer(inv -> { latch.countDown(); return null; })
                .when(mockSession).complete();

        AdkEventAdapter.bridge(Flowable.just(event), mockSession);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        verify(mockSession).complete();
    }

    @Test
    void errorEventCallsSessionError() throws Exception {
        var event = createErrorEvent("Something went wrong");
        var latch = new CountDownLatch(1);

        doAnswer(inv -> { latch.countDown(); return null; })
                .when(mockSession).error(any(Throwable.class));

        AdkEventAdapter.bridge(Flowable.just(event), mockSession);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(Throwable.class);
        verify(mockSession).error(captor.capture());
        assertEquals("Something went wrong", captor.getValue().getMessage());
    }

    @Test
    void flowableErrorCallsSessionError() throws Exception {
        var latch = new CountDownLatch(1);

        doAnswer(inv -> { latch.countDown(); return null; })
                .when(mockSession).error(any(Throwable.class));

        AdkEventAdapter.bridge(
                Flowable.error(new RuntimeException("stream failed")),
                mockSession
        );
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(Throwable.class);
        verify(mockSession).error(captor.capture());
        assertEquals("stream failed", captor.getValue().getMessage());
    }

    @Test
    void cancelStopsSubscription() throws Exception {
        var subject = PublishSubject.<Event>create();
        var adapter = AdkEventAdapter.bridge(subject.toFlowable(io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER), mockSession);

        // Send one event
        subject.onNext(createPartialEvent("token1"));
        Thread.sleep(100);
        verify(mockSession).send("token1");

        // Cancel
        adapter.cancel();
        verify(mockSession).complete();

        // Further events should not reach session
        subject.onNext(createPartialEvent("token2"));
        Thread.sleep(100);
        verify(mockSession, never()).send("token2");
    }

    @Test
    void extractTextFromMultipleParts() {
        var event = Event.builder()
                .id("test")
                .invocationId("inv-1")
                .author("model")
                .actions(EventActions.builder().build())
                .content(Optional.of(Content.fromParts(
                        Part.fromText("Hello"),
                        Part.fromText(" world")
                )))
                .build();

        var text = AdkEventAdapter.extractText(event);
        assertTrue(text.isPresent());
        assertEquals("Hello world", text.get());
    }

    @Test
    void extractTextEmptyWhenNoContent() {
        var event = Event.builder()
                .id("test")
                .invocationId("inv-1")
                .author("model")
                .actions(EventActions.builder().build())
                .build();

        var text = AdkEventAdapter.extractText(event);
        assertTrue(text.isEmpty());
    }

    @Test
    void bridgeWithBroadcasterCreateSession() {
        var broadcaster = mock(Broadcaster.class);
        var event = createTurnCompleteEvent(null);

        // This tests the Broadcaster-based factory path
        var adapter = AdkEventAdapter.bridge(Flowable.just(event), broadcaster);
        assertNotNull(adapter.session());
        assertFalse(adapter.session().sessionId().isEmpty());
    }

    @Test
    void bridgeWithSessionIdAndBroadcaster() {
        var broadcaster = mock(Broadcaster.class);
        var event = createTurnCompleteEvent(null);

        var adapter = AdkEventAdapter.bridge(Flowable.just(event), "my-session", broadcaster);
        assertEquals("my-session", adapter.session().sessionId());
    }

    // --- Helpers ---

    private Event createPartialEvent(String text) {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId("inv-1")
                .author("model")
                .actions(EventActions.builder().build())
                .partial(Optional.of(true))
                .content(Optional.of(Content.fromParts(Part.fromText(text))))
                .build();
    }

    private Event createTurnCompleteEvent(String text) {
        var builder = Event.builder()
                .id(Event.generateEventId())
                .invocationId("inv-1")
                .author("model")
                .actions(EventActions.builder().build())
                .turnComplete(Optional.of(true));
        if (text != null) {
            builder.content(Optional.of(Content.fromParts(Part.fromText(text))));
        }
        return builder.build();
    }

    private Event createErrorEvent(String message) {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId("inv-1")
                .author("model")
                .actions(EventActions.builder().build())
                .errorMessage(Optional.of(message))
                .build();
    }
}
