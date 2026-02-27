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
package org.atmosphere.ai.fanout;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates multi-model fan-out streaming: sends the same prompt to N models
 * simultaneously, with each model streaming tokens through its own child session.
 *
 * <p>This is the Broadcaster pattern applied to model routing. Each model endpoint
 * gets a child {@link StreamingSession} with a sessionId of
 * {@code parentSessionId + "-" + modelEndpoint.id()}. All child sessions broadcast
 * through the same {@link Broadcaster}, so the client receives interleaved token
 * streams that are distinguishable by sessionId.</p>
 *
 * <h3>Strategies</h3>
 * <ul>
 *   <li>{@link FanOutStrategy.AllResponses} — all models stream to completion</li>
 *   <li>{@link FanOutStrategy.FirstComplete} — first to finish wins, others are cancelled</li>
 *   <li>{@link FanOutStrategy.FastestTokens} — fastest token producer wins after N tokens</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var endpoints = List.of(
 *     new ModelEndpoint("gemini", geminiClient, "gemini-2.5-flash"),
 *     new ModelEndpoint("gpt4", openaiClient, "gpt-4o")
 * );
 * try (var fanOut = new FanOutStreamingSession(session, endpoints,
 *         new FanOutStrategy.AllResponses(), resource)) {
 *     fanOut.fanOut(ChatCompletionRequest.of("ignored", userPrompt));
 * }
 * }</pre>
 */
public final class FanOutStreamingSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FanOutStreamingSession.class);

    private final StreamingSession parentSession;
    private final List<ModelEndpoint> endpoints;
    private final FanOutStrategy strategy;
    private final AtmosphereResource resource;
    private final Broadcaster broadcaster;
    private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, FanOutResult> results = new ConcurrentHashMap<>();

    /**
     * Create a fan-out session backed by an {@link AtmosphereResource}.
     * Child sessions use the resource's broadcaster.
     *
     * @param parentSession the parent streaming session
     * @param endpoints     the model endpoints to fan out to
     * @param strategy      the fan-out strategy
     * @param resource      the atmosphere resource (provides the broadcaster)
     */
    public FanOutStreamingSession(StreamingSession parentSession, List<ModelEndpoint> endpoints,
                                  FanOutStrategy strategy, AtmosphereResource resource) {
        this.parentSession = parentSession;
        this.endpoints = List.copyOf(endpoints);
        this.strategy = strategy;
        this.resource = resource;
        this.broadcaster = null;
    }

    /**
     * Create a fan-out session backed by a {@link Broadcaster} directly.
     * Use this when no specific resource is available (e.g., MCP tool calls).
     *
     * @param parentSession the parent streaming session
     * @param endpoints     the model endpoints to fan out to
     * @param strategy      the fan-out strategy
     * @param broadcaster   the broadcaster to stream through
     */
    public FanOutStreamingSession(StreamingSession parentSession, List<ModelEndpoint> endpoints,
                                  FanOutStrategy strategy, Broadcaster broadcaster) {
        this.parentSession = parentSession;
        this.endpoints = List.copyOf(endpoints);
        this.strategy = strategy;
        this.resource = null;
        this.broadcaster = broadcaster;
    }

    /**
     * Fan out the given request to all configured model endpoints.
     * The model field in the request is overridden per endpoint.
     * This method blocks until all models complete (or the strategy selects a winner).
     *
     * @param baseRequest the base chat completion request (model field is overridden per endpoint)
     */
    public void fanOut(ChatCompletionRequest baseRequest) {
        if (endpoints.isEmpty()) {
            parentSession.complete();
            return;
        }

        // Announce fan-out to client
        parentSession.sendMetadata("fanout.models",
                endpoints.stream().map(ModelEndpoint::id).toList());

        var latch = new CountDownLatch(endpoints.size());
        var firstComplete = new AtomicReference<String>();
        var tokenCounts = new ConcurrentHashMap<String, AtomicInteger>();

        for (var endpoint : endpoints) {
            var childSessionId = parentSession.sessionId() + "-" + endpoint.id();
            var childSession = createChildSession(childSessionId);

            // Wrap child session to track results and apply strategy
            var tracker = new TrackingSession(childSession, endpoint.id(),
                    latch, firstComplete, tokenCounts);

            var finalRequest = new ChatCompletionRequest(endpoint.model(), baseRequest.messages(),
                    baseRequest.temperature(), baseRequest.maxTokens());
            var thread = Thread.ofVirtual().name("fanout-" + endpoint.id()).start(() -> {
                try {
                    endpoint.client().streamChatCompletion(finalRequest, tracker);
                } catch (Exception e) {
                    logger.error("Fan-out error for model {}: {}", endpoint.id(), e.getMessage());
                    if (!tracker.isClosed()) {
                        tracker.error(e);
                    }
                }
            });
            threads.add(thread);
        }

        // Wait based on strategy
        try {
            switch (strategy) {
                case FanOutStrategy.AllResponses() -> latch.await();
                case FanOutStrategy.FirstComplete() -> {
                    // Wait for any one to complete
                    while (firstComplete.get() == null && latch.getCount() > 0) {
                        Thread.sleep(10);
                    }
                    cancelAllExcept(firstComplete.get());
                }
                case FanOutStrategy.FastestTokens(var threshold) -> {
                    // Wait until any model hits the threshold
                    while (!closed.get()) {
                        var winner = tokenCounts.entrySet().stream()
                                .filter(e -> e.getValue().get() >= threshold)
                                .map(java.util.Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                        if (winner != null) {
                            cancelAllExcept(winner);
                            break;
                        }
                        if (latch.getCount() == 0) break;
                        Thread.sleep(10);
                    }
                    // Wait for remaining to finish
                    latch.await();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            parentSession.error(e);
            return;
        }

        // Signal fan-out complete
        parentSession.sendMetadata("fanout.complete", true);
        if (!parentSession.isClosed()) {
            parentSession.complete();
        }
    }

    /**
     * Get the results from all completed model calls.
     *
     * @return unmodifiable map of model ID to result
     */
    public java.util.Map<String, FanOutResult> getResults() {
        return Collections.unmodifiableMap(results);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (var thread : threads) {
                thread.interrupt();
            }
        }
    }

    private StreamingSession createChildSession(String sessionId) {
        if (resource != null) {
            return StreamingSessions.start(sessionId, resource);
        } else {
            return StreamingSessions.start(sessionId, broadcaster);
        }
    }

    private void cancelAllExcept(String winnerId) {
        closed.set(true);
        for (int i = 0; i < endpoints.size(); i++) {
            if (!endpoints.get(i).id().equals(winnerId) && i < threads.size()) {
                threads.get(i).interrupt();
            }
        }
    }

    /**
     * Wrapper session that tracks timing, token count, and aggregated response.
     */
    private final class TrackingSession implements StreamingSession {
        private final StreamingSession delegate;
        private final String modelId;
        private final CountDownLatch latch;
        private final AtomicReference<String> firstComplete;
        private final ConcurrentHashMap<String, AtomicInteger> tokenCounts;

        private final long startTime = System.currentTimeMillis();
        private volatile long firstTokenTime = -1;
        private final StringBuilder responseBuilder = new StringBuilder();
        private final AtomicInteger localTokenCount = new AtomicInteger(0);
        private final AtomicBoolean done = new AtomicBoolean(false);

        TrackingSession(StreamingSession delegate, String modelId,
                        CountDownLatch latch, AtomicReference<String> firstComplete,
                        ConcurrentHashMap<String, AtomicInteger> tokenCounts) {
            this.delegate = delegate;
            this.modelId = modelId;
            this.latch = latch;
            this.firstComplete = firstComplete;
            this.tokenCounts = tokenCounts;
            tokenCounts.put(modelId, new AtomicInteger(0));
        }

        @Override
        public String sessionId() {
            return delegate.sessionId();
        }

        @Override
        public void send(String token) {
            if (closed.get() && !modelId.equals(firstComplete.get())) {
                return; // cancelled
            }
            if (firstTokenTime < 0) {
                firstTokenTime = System.currentTimeMillis();
            }
            localTokenCount.incrementAndGet();
            tokenCounts.get(modelId).incrementAndGet();
            responseBuilder.append(token);
            delegate.send(token);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            delegate.sendMetadata(key, value);
        }

        @Override
        public void progress(String message) {
            delegate.progress(message);
        }

        @Override
        public void complete() {
            if (done.compareAndSet(false, true)) {
                recordResult(null);
                delegate.complete();
                firstComplete.compareAndSet(null, modelId);
                latch.countDown();
            }
        }

        @Override
        public void complete(String summary) {
            if (done.compareAndSet(false, true)) {
                recordResult(summary);
                delegate.complete(summary);
                firstComplete.compareAndSet(null, modelId);
                latch.countDown();
            }
        }

        @Override
        public void error(Throwable t) {
            if (done.compareAndSet(false, true)) {
                delegate.error(t);
                latch.countDown();
            }
        }

        @Override
        public boolean isClosed() {
            return done.get() || delegate.isClosed();
        }

        private void recordResult(String summary) {
            var elapsed = System.currentTimeMillis() - startTime;
            var ttft = firstTokenTime > 0 ? firstTokenTime - startTime : elapsed;
            var response = summary != null ? summary : responseBuilder.toString();
            results.put(modelId, new FanOutResult(modelId, response, ttft, elapsed, localTokenCount.get()));
        }
    }
}
