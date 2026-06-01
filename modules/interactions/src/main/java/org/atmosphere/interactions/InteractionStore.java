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
package org.atmosphere.interactions;

import java.util.List;
import java.util.Optional;

/**
 * Durable persistence SPI for {@link Interaction}s and their {@link InteractionStep}
 * logs — the half of the Interactions API that {@code RunRegistry} (ephemeral,
 * in-memory, 30-minute TTL) does not cover: a record that survives run completion
 * and a JVM restart so a background interaction is retrievable long after the
 * client disconnected.
 *
 * <p>In-tree implementations: {@link InMemoryInteractionStore} (default, always
 * available) and {@code SqliteInteractionStore} (durable). Redis/Postgres backends
 * are pluggable via this SPI but are not shipped in-tree.</p>
 *
 * <p>Implementations must be thread-safe: {@link #appendStep} is called from the
 * run thread while {@link #load}/{@link #list} may be called concurrently from
 * request threads.</p>
 */
public interface InteractionStore {

    /** Initialize the backend (open connections, create schema). Idempotent. */
    void start();

    /**
     * Release backend resources. Idempotent and symmetric with {@link #start()}:
     * a store closes only what it opened (Correctness Invariant #1 — Ownership).
     */
    void stop();

    /**
     * Whether this store's backend is configured and ready. Used by
     * {@code ServiceLoader} auto-detection to skip stores whose backend is
     * absent (e.g. no SQLite driver on the classpath).
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Insert or replace an interaction's header row (status, finalText, usage,
     * error, timestamps). Steps are appended separately via {@link #appendStep}.
     *
     * @param interaction the interaction to persist
     * @return the persisted interaction (echoed for fluent use)
     */
    Interaction save(Interaction interaction);

    /**
     * Append one step to an interaction's durable log. Called incrementally as
     * the run produces events so a {@code get} on an in-flight background run
     * observes partial progress.
     *
     * @param interactionId the owning interaction id
     * @param step          the step to append
     */
    void appendStep(String interactionId, InteractionStep step);

    /**
     * Load an interaction with its full ordered step log.
     *
     * @param interactionId the id to load
     * @return the interaction, or empty if unknown (or {@code store=false})
     */
    Optional<Interaction> load(String interactionId);

    /**
     * List interactions matching the query, newest first, bounded by
     * {@link InteractionQuery#limit()}.
     *
     * @param query the filter (never {@code null})
     * @return matching interactions, at most {@code query.limit()}
     */
    List<Interaction> list(InteractionQuery query);

    /**
     * Delete an interaction and its steps.
     *
     * @param interactionId the id to delete
     * @return {@code true} if a row was removed, {@code false} if unknown
     */
    boolean delete(String interactionId);
}
