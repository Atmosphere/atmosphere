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
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges Google ADK's {@link Flowable}&lt;{@link Event}&gt; stream to an
 * Atmosphere {@link StreamingSession}.
 *
 * <p>Subscribes to the event stream from an ADK {@link com.google.adk.runner.Runner}
 * and forwards streaming tokens to connected browser clients via Atmosphere's
 * Broadcaster infrastructure.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Flowable<Event> events = runner.runAsync(userId, sessionId, userMessage);
 * AdkEventAdapter adapter = AdkEventAdapter.bridge(events, broadcaster);
 * // tokens are now pushed to all WebSocket/SSE/gRPC clients on the broadcaster
 * }</pre>
 *
 * @see StreamingSession
 * @see StreamingSessions
 */
public final class AdkEventAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AdkEventAdapter.class);

    private final StreamingSession session;
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

    private AdkEventAdapter(StreamingSession session) {
        this.session = session;
    }

    /**
     * Bridge an ADK event stream to a Broadcaster, creating a new
     * {@link StreamingSession} automatically.
     *
     * @param events      the ADK event stream from {@code Runner.runAsync()}
     * @param broadcaster the Atmosphere broadcaster to push tokens to
     * @return the adapter (for lifecycle management)
     */
    public static AdkEventAdapter bridge(Flowable<Event> events, Broadcaster broadcaster) {
        var session = StreamingSessions.start(broadcaster);
        return bridge(events, session);
    }

    /**
     * Bridge an ADK event stream to a Broadcaster with a specific session ID.
     *
     * @param events      the ADK event stream from {@code Runner.runAsync()}
     * @param sessionId   session ID for correlation
     * @param broadcaster the Atmosphere broadcaster to push tokens to
     * @return the adapter (for lifecycle management)
     */
    public static AdkEventAdapter bridge(Flowable<Event> events, String sessionId, Broadcaster broadcaster) {
        var session = StreamingSessions.start(sessionId, broadcaster);
        return bridge(events, session);
    }

    /**
     * Bridge an ADK event stream to an existing {@link StreamingSession}.
     *
     * @param events  the ADK event stream from {@code Runner.runAsync()}
     * @param session the streaming session to push tokens to
     * @return the adapter (for lifecycle management)
     */
    public static AdkEventAdapter bridge(Flowable<Event> events, StreamingSession session) {
        var adapter = new AdkEventAdapter(session);
        adapter.subscribe(events);
        return adapter;
    }

    /**
     * Cancel the subscription and close the session.
     */
    public void cancel() {
        var disposable = subscription.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        if (!session.isClosed()) {
            session.complete();
        }
    }

    /**
     * Returns the underlying streaming session.
     */
    public StreamingSession session() {
        return session;
    }

    private void subscribe(Flowable<Event> events) {
        var disposable = events.subscribe(
                this::onEvent,
                this::onError,
                this::onComplete
        );
        subscription.set(disposable);
    }

    private void onEvent(Event event) {
        // Handle error events
        if (event.errorMessage().isPresent()) {
            logger.warn("ADK error event: {}", event.errorMessage().get());
            if (completed.compareAndSet(false, true)) {
                session.error(new RuntimeException(event.errorMessage().get()));
            }
            return;
        }

        // Extract text from partial streaming chunks
        if (event.partial().orElse(false)) {
            extractText(event).ifPresent(session::send);
            return;
        }

        // Handle turn completion
        if (event.turnComplete().orElse(false)) {
            if (completed.compareAndSet(false, true)) {
                var summary = extractText(event).orElse(null);
                if (summary != null) {
                    session.complete(summary);
                } else {
                    session.complete();
                }
            }
            return;
        }

        // For non-partial, non-turnComplete events with content, send as token
        extractText(event).ifPresent(session::send);
    }

    private void onError(Throwable t) {
        logger.error("ADK event stream error", t);
        if (completed.compareAndSet(false, true)) {
            session.error(t);
        }
    }

    private void onComplete() {
        if (completed.compareAndSet(false, true)) {
            session.complete();
        }
    }

    /**
     * Extract text content from an ADK Event.
     */
    static Optional<String> extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .flatMap(AdkEventAdapter::joinPartTexts);
    }

    private static Optional<String> joinPartTexts(List<Part> parts) {
        var sb = new StringBuilder();
        for (var part : parts) {
            part.text().ifPresent(sb::append);
        }
        return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString());
    }
}
