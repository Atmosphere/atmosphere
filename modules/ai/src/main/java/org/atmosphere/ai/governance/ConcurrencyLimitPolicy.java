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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Cap the number of <i>in-flight</i> (simultaneously-evaluating) requests
 * per subject. The pre-admission phase decrements the counter; the
 * post-response phase increments it back. Complementary to
 * {@link RateLimitPolicy}, which caps <i>over time</i>:
 *
 * <ul>
 *   <li>{@code RateLimitPolicy}: "no more than 30 requests per minute"</li>
 *   <li>{@code ConcurrencyLimitPolicy}: "no more than 3 streams open
 *       simultaneously"</li>
 * </ul>
 *
 * <p>Typical use: a chat endpoint backed by an expensive LLM. Even if a
 * user's rate is fine, 20 concurrent streams explode the back-pressure
 * budget. A concurrency cap of 2-3 keeps per-user cost bounded.</p>
 *
 * <h2>Pair with the pipeline</h2>
 * The post-response phase on this policy is what releases the slot. For
 * runtimes that don't evaluate policies post-response, operators
 * explicitly call {@link #release(String)} from their session-complete
 * hook — the policy's counter API is public for exactly that.
 *
 * <h2>Correctness invariant #2 (terminal path completeness)</h2>
 * Every acquired slot must be released: on success via post-response
 * evaluation; on error via a {@code session.onError} hook; on cancel via
 * the streaming session's cleanup. The framework-level guardrail
 * integration handles this — direct callers outside the pipeline must
 * guarantee the release themselves.
 */
public final class ConcurrencyLimitPolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final int maxConcurrent;
    private final Function<AiRequest, String> subjectOf;
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public ConcurrencyLimitPolicy(String name, int maxConcurrent) {
        this(name, "code:" + ConcurrencyLimitPolicy.class.getName(), "1",
                maxConcurrent, ConcurrencyLimitPolicy::defaultSubject);
    }

    public ConcurrencyLimitPolicy(String name, String source, String version,
                                  int maxConcurrent,
                                  Function<AiRequest, String> subjectOf) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be > 0, got: " + maxConcurrent);
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.maxConcurrent = maxConcurrent;
        this.subjectOf = Objects.requireNonNull(subjectOf, "subjectOf");
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public int maxConcurrent() { return maxConcurrent; }

    /** Current in-flight count for {@code subject}. Exposed for admin introspection. */
    public int inFlightFor(String subject) {
        var counter = inFlight.get(subject);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Explicit release — called by session hooks that complete outside
     * the pipeline's post-response evaluation path (error / cancel).
     * Idempotent-ish: never drives the counter below zero.
     */
    public void release(String subject) {
        var counter = inFlight.get(subject);
        if (counter == null) return;
        counter.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /** Reset all subject counters. Tests / ops runbooks. */
    public void reset() {
        inFlight.clear();
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var subject = subjectOf.apply(context.request());
        if (subject == null || subject.isBlank()) {
            subject = "anonymous";
        }
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            release(subject);
            return PolicyDecision.admit();
        }
        // Pre-admission — try to acquire a slot. CAS loop so a slot vacated
        // between our read and the increment doesn't yield a stale deny.
        var counter = inFlight.computeIfAbsent(subject, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= maxConcurrent) {
                return PolicyDecision.deny("concurrency limit: " + subject
                        + " already has " + current + " in-flight (max " + maxConcurrent + ")");
            }
            if (counter.compareAndSet(current, current + 1)) {
                return PolicyDecision.admit();
            }
        }
    }

    private static String defaultSubject(AiRequest req) {
        if (req == null) return "anonymous";
        if (req.userId() != null && !req.userId().isBlank()) return "user:" + req.userId();
        if (req.sessionId() != null && !req.sessionId().isBlank()) return "session:" + req.sessionId();
        return "anonymous";
    }
}
