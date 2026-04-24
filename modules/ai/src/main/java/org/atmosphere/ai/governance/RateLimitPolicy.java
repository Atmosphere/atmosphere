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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Sliding-window rate limit expressed as a {@link GovernancePolicy}.
 * Each incoming request is attributed to a <i>subject</i> (user, session,
 * tenant — whatever {@link #subjectOf} extracts from {@link AiRequest}),
 * counted into a per-subject timestamp ring, and compared against the
 * configured {@code limit / window} pair. Over-limit requests are denied
 * with a {@code rate-limited} reason.
 *
 * <h2>Subject extraction</h2>
 * The default extractor keys on {@link AiRequest#userId()}, falling back
 * to {@link AiRequest#sessionId()} and then to {@code "anonymous"}.
 * Deployments that key on tenant, api-key, or IP build a custom extractor
 * and pass it via {@link #withSubjectOf(Function)}.
 *
 * <h2>Post-response phase</h2>
 * Always admits post-response — rate limiting is a pre-admission concern.
 *
 * <h2>Memory footprint</h2>
 * {@code O(subjects × limit)} timestamps. For deployments with unbounded
 * subject cardinality, pair with an external cache-backed subject table
 * (planned SPI); this impl is intended for bounded-cardinality scenarios
 * (paid tenants, authenticated users) and one-process deployments.
 */
public final class RateLimitPolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Function<AiRequest, String> subjectOf;
    private final ConcurrentHashMap<String, Deque<Instant>> timestamps = new ConcurrentHashMap<>();

    public RateLimitPolicy(String name, int limit, Duration window) {
        this(name, "code:" + RateLimitPolicy.class.getName(), "1",
                limit, window, Clock.systemUTC(), RateLimitPolicy::defaultSubject);
    }

    public RateLimitPolicy(String name, String source, String version,
                           int limit, Duration window,
                           Clock clock, Function<AiRequest, String> subjectOf) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, got: " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.limit = limit;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subjectOf = Objects.requireNonNull(subjectOf, "subjectOf");
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public int limit() { return limit; }
    public Duration window() { return window; }

    /** Immutable copy with a replacement subject extractor. */
    public RateLimitPolicy withSubjectOf(Function<AiRequest, String> extractor) {
        return new RateLimitPolicy(name, source, version, limit, window, clock, extractor);
    }

    /** Current hit count for the subject within the window. Exposed for admin introspection. */
    public int currentHits(String subject) {
        var deque = timestamps.get(subject);
        if (deque == null) return 0;
        var cutoff = clock.instant().minus(window);
        synchronized (deque) {
            pruneStale(deque, cutoff);
            return deque.size();
        }
    }

    /** Reset — intended for tests. */
    public void reset() {
        timestamps.clear();
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        var subject = subjectOf.apply(context.request());
        if (subject == null || subject.isBlank()) {
            subject = "anonymous";
        }
        var now = clock.instant();
        var cutoff = now.minus(window);

        // computeIfAbsent + synchronized deque gives a single-writer lock per
        // subject; contended across subjects at the map level (CHM's striping
        // absorbs that), uncontended within a subject for the common path.
        var deque = timestamps.computeIfAbsent(subject, k -> new ArrayDeque<>());
        synchronized (deque) {
            pruneStale(deque, cutoff);
            if (deque.size() >= limit) {
                return PolicyDecision.deny(
                        "rate-limited: " + subject + " exceeded "
                                + limit + " requests per " + window.toSeconds() + "s");
            }
            deque.addLast(now);
            return PolicyDecision.admit();
        }
    }

    private static void pruneStale(Deque<Instant> deque, Instant cutoff) {
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.removeFirst();
        }
    }

    private static String defaultSubject(AiRequest req) {
        if (req == null) return "anonymous";
        if (req.userId() != null && !req.userId().isBlank()) return "user:" + req.userId();
        if (req.sessionId() != null && !req.sessionId().isBlank()) return "session:" + req.sessionId();
        return "anonymous";
    }
}
