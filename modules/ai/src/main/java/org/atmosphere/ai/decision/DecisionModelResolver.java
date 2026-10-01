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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resolves the {@link DecisionModel} to use, in order:
 * <ol>
 *   <li>the available {@code META-INF/services/org.atmosphere.ai.decision.DecisionModel}
 *       registration with the highest {@link DecisionModel#priority()};</li>
 *   <li>otherwise a {@link RuntimeDecisionModel} over the resolved
 *       {@link org.atmosphere.ai.AgentRuntime}, when it is available — that is,
 *       when the runtime is not the {@code DemoAgentRuntime} canned fallback;</li>
 *   <li>otherwise empty: no model can answer.</li>
 * </ol>
 *
 * <p>The fallback {@link RuntimeDecisionModel} is one instance shared by every
 * consumer that resolves through here (the LLM injection, scope and moderation
 * tiers, and an {@link org.atmosphere.ai.intent.IntentRouting} with no decision
 * model of its own, which asks one question per admitted request), so its
 * concurrency bound is shared too. It allows
 * {@link RuntimeDecisionModel#DEFAULT_MAX_CONCURRENCY} questions in flight unless
 * the {@value #MAX_CONCURRENCY_PROPERTY} JVM system property sets another
 * positive integer; the property is read when the fallback is built, the first
 * time a resolution finds it (and again after {@link #reset()}). A registered
 * {@link DecisionModel} sizes itself.</p>
 *
 * <p>A registration this scan instantiated but did not select (unavailable,
 * outranked, or whose availability check threw) is closed when it is
 * {@link AutoCloseable}: each scan's {@link ServiceLoader} creates its own
 * instances, so nothing else holds them.</p>
 *
 * <p>Only a non-empty result is cached. Resolution can run before the
 * application's {@link org.atmosphere.ai.AiConfig} is installed, when only the
 * demo fallback is available; caching that empty answer would pin it for the
 * life of the JVM (the same rule {@link org.atmosphere.ai.EmbeddingRuntimeResolver}
 * follows). A selected registration is cached until {@link #reset()}. The
 * fallback is cached the same way, except when the scan that chose it found a
 * registration that was unavailable or whose availability check threw (an
 * external endpoint down at boot, say): then the fallback is provisional and
 * one caller rescans every {@link #FALLBACK_RECHECK_INTERVAL}, while the others
 * keep the cached fallback. A registration that has become available replaces
 * it; otherwise the same fallback instance stays, so its concurrency bound stays
 * shared. A consumer that kept the model it resolved (the {@code LLM_CLASSIFIER}
 * injection tier builds its classifier once, until
 * {@code InjectionClassifierResolver.reset()}) keeps that model.</p>
 *
 * <p>{@link #reset()} closes the cached model when it is {@link AutoCloseable}:
 * the resolver instantiated it through {@link ServiceLoader}, so it owns it.
 * Nothing else closes it; a selected registration lives until {@code reset()}
 * or the end of the JVM.</p>
 */
public final class DecisionModelResolver {

    private static final Logger logger = LoggerFactory.getLogger(DecisionModelResolver.class);

    /**
     * JVM system property: questions in flight on the fallback
     * {@link RuntimeDecisionModel}, a positive integer. Unset, or not a positive
     * integer (logged), means {@link RuntimeDecisionModel#DEFAULT_MAX_CONCURRENCY}.
     */
    public static final String MAX_CONCURRENCY_PROPERTY = "org.atmosphere.ai.decision.max-concurrency";

    /** Bound on broken provider entries skipped in one scan. */
    private static final int MAX_LOAD_ERRORS = 64;

    /**
     * How long a provisional fallback is kept before one caller rescans for a
     * registration that was unavailable when the fallback was chosen.
     */
    public static final Duration FALLBACK_RECHECK_INTERVAL = Duration.ofSeconds(30);

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile Resolution cached;

    /**
     * A cached model; {@code provisional} when it is the fallback chosen while a
     * registration was unavailable, rescanned once {@code recheckAtNanos} passes.
     */
    private record Resolution(DecisionModel model, boolean provisional, long recheckAtNanos) {

        boolean current() {
            return !provisional || System.nanoTime() - recheckAtNanos < 0;
        }
    }

    /** One scan's result, and whether a loaded registration was not available. */
    private record Scan(Optional<DecisionModel> model, boolean fallback, boolean registrationUnavailable) {
    }

    private DecisionModelResolver() {
    }

    /** The decision model to use, or empty when none can answer. */
    public static Optional<DecisionModel> resolve() {
        var resolution = cached;
        if (resolution != null && resolution.current()) {
            return Optional.of(resolution.model());
        }
        if (resolution != null) {
            // A provisional fallback is due for a recheck. One caller rescans;
            // the others keep the fallback instead of queueing behind the scan.
            if (!LOCK.tryLock()) {
                return Optional.of(resolution.model());
            }
        } else {
            LOCK.lock();
        }
        try {
            resolution = cached;
            if (resolution != null && resolution.current()) {
                return Optional.of(resolution.model());
            }
            var previous = resolution == null ? null : resolution.model();
            var scan = scan(previous instanceof RuntimeDecisionModel fallback ? fallback : null);
            if (scan.model().isEmpty()) {
                cached = null;
                return Optional.empty();
            }
            var model = scan.model().get();
            var provisional = scan.fallback() && scan.registrationUnavailable();
            cached = new Resolution(model, provisional,
                    System.nanoTime() + FALLBACK_RECHECK_INTERVAL.toNanos());
            if (model != previous) {
                logger.info("DecisionModel resolved: {}", model.name());
                release(previous);
            }
            return Optional.of(model);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Forget the cached model so the next {@link #resolve()} rescans, and close
     * it when it is {@link AutoCloseable}: the resolver created it, and nothing
     * else closes it. A consumer still holding it then gets that model's
     * closed behaviour (a closed registration should fail its questions).
     */
    public static void reset() {
        Resolution dropped;
        LOCK.lock();
        try {
            dropped = cached;
            cached = null;
        } finally {
            LOCK.unlock();
        }
        if (dropped != null) {
            release(dropped.model());
        }
    }

    /** Test hook: make a provisional fallback due for its recheck now. */
    static void expireFallbackRecheck() {
        LOCK.lock();
        try {
            var resolution = cached;
            if (resolution != null && resolution.provisional()) {
                cached = new Resolution(resolution.model(), true, System.nanoTime());
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Scan the registrations, else build the fallback. {@code previousFallback},
     * when set, is reused instead of a new fallback so a recheck that still ends
     * at the fallback keeps one shared instance.
     */
    private static Scan scan(RuntimeDecisionModel previousFallback) {
        var registrationUnavailable = false;
        DecisionModel best = null;
        var iterator = ServiceLoader.load(DecisionModel.class).iterator();
        var loadErrors = 0;
        while (loadErrors <= MAX_LOAD_ERRORS) {
            DecisionModel candidate;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                candidate = iterator.next();
            } catch (ServiceConfigurationError e) {
                // ServiceLoader keeps going after a broken provider.
                logger.debug("Skipping a DecisionModel provider that failed to load", e);
                loadErrors++;
                continue;
            }
            var selected = false;
            try {
                if (!candidate.isAvailable()) {
                    registrationUnavailable = true;
                } else if (best == null || candidate.priority() > best.priority()) {
                    release(best);
                    best = candidate;
                    selected = true;
                }
            } catch (RuntimeException e) {
                registrationUnavailable = true;
                logger.debug("Skipping DecisionModel {}: availability check threw",
                        candidate.getClass().getName(), e);
            }
            if (!selected) {
                release(candidate);
            }
        }
        if (best != null) {
            return new Scan(Optional.of(best), false, registrationUnavailable);
        }
        var fallback = previousFallback != null ? previousFallback
                : new RuntimeDecisionModel(AgentRuntimeResolver.resolve(), fallbackMaxConcurrency(),
                        RuntimeDecisionModel.DEFAULT_MIN_OBSERVED_MASS);
        try {
            if (fallback.isAvailable()) {
                return new Scan(Optional.of(fallback), true, registrationUnavailable);
            }
        } catch (RuntimeException e) {
            logger.debug("Skipping {}: availability check threw", fallback.name(), e);
        }
        return new Scan(Optional.empty(), true, registrationUnavailable);
    }

    /**
     * Close a model the resolver instantiated and no longer hands out, when it
     * holds resources ({@link AutoCloseable}, e.g. an HTTP client): a candidate
     * a scan did not select, one a later candidate displaced, or the cached one
     * {@link #reset()} drops. Each scan's {@link ServiceLoader} creates fresh
     * instances, so the resolver owns them.
     */
    private static void release(DecisionModel candidate) {
        if (candidate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Interrupted closing DecisionModel {}", candidate.getClass().getName(), e);
            } catch (Exception e) {
                logger.debug("Closing DecisionModel {} failed", candidate.getClass().getName(), e);
            }
        }
    }

    /** The fallback's concurrency: {@value #MAX_CONCURRENCY_PROPERTY}, or the default. */
    static int fallbackMaxConcurrency() {
        var configured = System.getProperty(MAX_CONCURRENCY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY;
        }
        try {
            var value = Integer.parseInt(configured.trim());
            if (value >= 1) {
                return value;
            }
        } catch (NumberFormatException e) {
            logger.debug("{} is not an integer", MAX_CONCURRENCY_PROPERTY, e);
        }
        logger.warn("Ignoring {}={}: not a positive integer; using {}", MAX_CONCURRENCY_PROPERTY,
                configured, RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY);
        return RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY;
    }
}
