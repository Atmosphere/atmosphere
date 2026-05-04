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
import org.atmosphere.ai.AiEvent;
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
 * and forwards streaming texts to connected browser clients via Atmosphere's
 * Broadcaster infrastructure.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Flowable<Event> events = runner.runAsync(userId, sessionId, userMessage);
 * AdkEventAdapter adapter = AdkEventAdapter.bridge(events, broadcaster);
 * // streaming texts are now pushed to all WebSocket/SSE/gRPC clients on the broadcaster
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
    private final java.util.concurrent.CompletableFuture<Void> doneFuture = new java.util.concurrent.CompletableFuture<>();

    /**
     * Lifecycle hook bridge: optional listeners + model name passed in via the
     * lifecycle-aware {@code bridge(...)} overload. Default empty/{@code "adk"}
     * keeps the existing 3 bridge factories backward-compatible — when not
     * provided the fire calls are no-ops.
     */
    private final java.util.List<org.atmosphere.ai.AgentLifecycleListener> listeners;
    private final String modelName;
    private final long startNanos;
    private final java.util.concurrent.atomic.AtomicReference<org.atmosphere.ai.TokenUsage>
            lastUsage = new java.util.concurrent.atomic.AtomicReference<>();

    private AdkEventAdapter(StreamingSession session) {
        this(session, java.util.List.of(), "adk");
    }

    private AdkEventAdapter(
            StreamingSession session,
            java.util.List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            String modelName) {
        this.session = session;
        this.listeners = listeners != null ? listeners : java.util.List.of();
        this.modelName = modelName != null ? modelName : "adk";
        this.startNanos = System.nanoTime();
    }

    /**
     * Bridge an ADK event stream to a Broadcaster, creating a new
     * {@link StreamingSession} automatically.
     *
     * @param events      the ADK event stream from {@code Runner.runAsync()}
     * @param broadcaster the Atmosphere broadcaster to push streaming texts to
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
     * @param broadcaster the Atmosphere broadcaster to push streaming texts to
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
     * @param session the streaming session to push streaming texts to
     * @return the adapter (for lifecycle management)
     */
    public static AdkEventAdapter bridge(Flowable<Event> events, StreamingSession session) {
        var adapter = new AdkEventAdapter(session);
        adapter.subscribe(events);
        return adapter;
    }

    /**
     * Bridge an ADK event stream to an existing {@link StreamingSession}, with
     * model-lifecycle hook firing. Fires {@code fireModelStart} synchronously
     * before subscribing; {@code fireModelEnd} on normal completion (with the
     * last captured token usage); {@code fireModelError} on subscription
     * error. {@code messageCount} and {@code toolCount} are passed-through to
     * the {@code onModelStart} hook so observability consumers see the
     * dispatch shape (history depth, tool list size).
     */
    public static AdkEventAdapter bridge(
            Flowable<Event> events,
            StreamingSession session,
            java.util.List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            String modelName,
            int messageCount,
            int toolCount) {
        var adapter = new AdkEventAdapter(session, listeners, modelName);
        org.atmosphere.ai.AgentLifecycleListener.fireModelStart(
                adapter.listeners, adapter.modelName, messageCount, toolCount);
        adapter.subscribe(events);
        return adapter;
    }

    /**
     * Cancel the subscription and close the session.
     *
     * <p><b>Backend reclamation contract.</b> This method only disposes the
     * RxJava Flowable and completes the session — it does NOT touch the
     * Runner. ADK exposes no {@code Runner.cancel(invocationId)} API, so
     * per-invocation cancel on a shared Runner is not possible. However,
     * {@code AdkAgentRuntime.doExecuteWithHandle} does cooperate with this
     * method on the per-request-Runner path: when tools or a CacheHint
     * force a fresh Runner per request, the outer {@code ExecutionHandle}
     * also calls {@code Runner.close()} after delegating here, so that
     * branch actually reclaims backend Gemini compute. On the shared-Runner
     * path (no tools, no hint) the backend call still runs to completion
     * and bills the user's quota — closing the shared Runner would nuke
     * concurrent requests, so it is intentionally left alone until ADK
     * ships a per-invocation cancel primitive.</p>
     */
    public void cancel() {
        var disposable = subscription.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        if (!session.isClosed()) {
            session.complete();
        }
        doneFuture.complete(null);
    }

    /**
     * Returns the underlying streaming session.
     */
    public StreamingSession session() {
        return session;
    }

    /**
     * Future that completes when the ADK event stream terminates — normal
     * completion, error, or cancel. Used by {@code AdkAgentRuntime}'s
     * {@code doExecuteWithHandle} override to populate
     * {@link org.atmosphere.ai.ExecutionHandle#whenDone()}. (D-6 ADK native
     * cancel wiring.)
     */
    public java.util.concurrent.CompletableFuture<Void> whenDone() {
        return doneFuture;
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
                session.emit(new AiEvent.Error(
                        event.errorMessage().get(), "adk_error", false));
            }
            return;
        }

        // Emit agent step events for non-partial, non-terminal events (orchestration visibility)
        var author = event.author();
        if (author != null && !author.isEmpty()
                && !event.partial().orElse(false) && !event.turnComplete().orElse(false)) {
            session.emit(new AiEvent.AgentStep(
                    author, "ADK agent step from " + author, java.util.Map.of()));
        }

        // Emit tool call events (function calls and responses)
        var functionCalls = event.functionCalls();
        if (functionCalls != null && !functionCalls.isEmpty()) {
            for (var fc : functionCalls) {
                var toolName = fc.name().orElse("unknown");
                var args = fc.args().orElse(java.util.Map.of());
                session.emit(new AiEvent.ToolStart(toolName, args));
            }
        }
        var functionResponses = event.functionResponses();
        if (functionResponses != null && !functionResponses.isEmpty()) {
            for (var fr : functionResponses) {
                var toolName = fr.name().orElse("unknown");
                var result = fr.response().orElse(java.util.Map.of());
                session.emit(new AiEvent.ToolResult(toolName, result));
            }
        }

        // Extract text from partial streaming chunks
        if (event.partial().orElse(false)) {
            extractText(event).ifPresent(text ->
                    session.emit(new AiEvent.TextDelta(text)));
            return;
        }

        // Forward usage metadata if present (ADK 0.9.0+) and stash the latest
        // counts for the lifecycle-end hook (fired in onComplete) so consumers
        // see the final usage figure even when ADK emits multiple usage events
        // within a single tool-loop run.
        extractUsageMetadata(event, session).ifPresent(lastUsage::set);

        // Handle turn completion
        if (event.turnComplete().orElse(false)) {
            if (completed.compareAndSet(false, true)) {
                // The turnComplete event may contain the full response text
                // (not sent as partials). Emit it as a text delta before completing.
                extractText(event).ifPresent(text ->
                        session.emit(new AiEvent.TextDelta(text)));
                session.emit(new AiEvent.Complete(null, java.util.Map.of()));
            }
            return;
        }

        // For non-partial, non-turnComplete events with content, send as text delta
        extractText(event).ifPresent(text ->
                session.emit(new AiEvent.TextDelta(text)));
    }

    private void onError(Throwable t) {
        logger.error("ADK event stream error", t);
        // Fire model-lifecycle error hook before session.error so audit
        // appenders / AiEventForwardingListener see the failure even when
        // session.error short-circuits on already-closed sessions. No-op
        // when bridge() was called without listeners.
        org.atmosphere.ai.AgentLifecycleListener.fireModelError(listeners, modelName, t);
        if (completed.compareAndSet(false, true) && !session.isClosed()) {
            session.error(t);
        }
        // completeExceptionally so whenDone().get() surfaces the real error
        // to upstream listener chains. complete(null) here silently masked
        // failures as successes for any caller waiting on the future.
        doneFuture.completeExceptionally(t);
    }

    private void onComplete() {
        if (completed.compareAndSet(false, true)) {
            session.emit(new AiEvent.Complete(null, java.util.Map.of()));
        }
        // Fire model-lifecycle end hook with captured usage + duration. The
        // usage comes from extractUsageMetadata, which stores the latest
        // ADK-emitted TokenUsage in the lastUsage holder. Duration is
        // wall-clock from adapter construction (effectively the dispatch
        // start, since bridge() constructs and subscribes in the same call).
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        org.atmosphere.ai.AgentLifecycleListener.fireModelEnd(
                listeners, modelName, lastUsage.get(), durationMs);
        doneFuture.complete(null);
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

    /**
     * Extract usage metadata from an ADK event and forward token counts to the
     * session via the typed {@link org.atmosphere.ai.StreamingSession#usage}
     * sink. The default sink re-emits the legacy {@code ai.tokens.*} metadata
     * keys so existing consumers keep working unchanged.
     */
    private static Optional<org.atmosphere.ai.TokenUsage> extractUsageMetadata(
            Event event, StreamingSession session) {
        return event.usageMetadata().flatMap(usage -> {
            long input = usage.promptTokenCount().map(Integer::longValue).orElse(0L);
            long output = usage.candidatesTokenCount().map(Integer::longValue).orElse(0L);
            long total = usage.totalTokenCount().map(Integer::longValue).orElse(input + output);
            var tokenUsage = new org.atmosphere.ai.TokenUsage(input, output, 0L, total, null);
            if (tokenUsage.hasCounts()) {
                session.usage(tokenUsage);
                return Optional.of(tokenUsage);
            }
            return Optional.empty();
        });
    }
}
