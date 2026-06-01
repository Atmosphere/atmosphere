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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link InteractionStore} backed by a {@link ConcurrentHashMap}. The
 * default backend: always available, no durability across JVM restarts.
 *
 * <p>Bounded to {@link #DEFAULT_MAX_INTERACTIONS} entries; when the cap is
 * reached the oldest interaction (by {@code createdAt}) is evicted so an
 * external feed of interactions can never grow the map without limit
 * (Correctness Invariant #3 — Backpressure). The cap is logged when it bites
 * so silent truncation never reads as "retained everything."</p>
 */
public final class InMemoryInteractionStore implements InteractionStore {

    /** Default ceiling on retained interactions before oldest-first eviction. */
    public static final int DEFAULT_MAX_INTERACTIONS = 10_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryInteractionStore.class);

    private final ConcurrentHashMap<String, Interaction> interactions = new ConcurrentHashMap<>();
    private final int maxInteractions;
    private final Clock clock;

    public InMemoryInteractionStore() {
        this(DEFAULT_MAX_INTERACTIONS, Clock.systemUTC());
    }

    public InMemoryInteractionStore(int maxInteractions, Clock clock) {
        if (maxInteractions <= 0) {
            throw new IllegalArgumentException("maxInteractions must be positive, got " + maxInteractions);
        }
        this.maxInteractions = maxInteractions;
        this.clock = clock;
    }

    @Override
    public void start() {
        // No backend to initialize.
    }

    @Override
    public void stop() {
        interactions.clear();
    }

    @Override
    public Interaction save(Interaction interaction) {
        interactions.put(interaction.id(), interaction);
        evictIfNeeded();
        return interaction;
    }

    @Override
    public void appendStep(String interactionId, InteractionStep step) {
        var updated = interactions.computeIfPresent(interactionId,
                (id, current) -> current.withAppendedStep(step, Instant.now(clock)));
        if (updated == null) {
            // Appending to an unknown interaction is a no-op rather than an error:
            // a store=false run has no header row by design, and a stale append
            // after delete must not resurrect a row.
            LOGGER.debug("appendStep ignored — no interaction {} in store", interactionId);
        }
    }

    @Override
    public Optional<Interaction> load(String interactionId) {
        return Optional.ofNullable(interactions.get(interactionId));
    }

    @Override
    public List<Interaction> list(InteractionQuery query) {
        var matches = new ArrayList<Interaction>();
        for (var interaction : interactions.values()) {
            if (query.matches(interaction)) {
                matches.add(interaction);
            }
        }
        matches.sort(Comparator.comparing(Interaction::createdAt).reversed());
        if (matches.size() > query.limit()) {
            return List.copyOf(matches.subList(0, query.limit()));
        }
        return List.copyOf(matches);
    }

    @Override
    public boolean delete(String interactionId) {
        return interactions.remove(interactionId) != null;
    }

    /** Current number of retained interactions (test/observability aid). */
    public int size() {
        return interactions.size();
    }

    private void evictIfNeeded() {
        while (interactions.size() > maxInteractions) {
            var oldest = interactions.values().stream()
                    .min(Comparator.comparing(Interaction::createdAt))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            interactions.remove(oldest.id());
            LOGGER.debug("Evicted oldest interaction {} — cap {} reached",
                    oldest.id(), maxInteractions);
        }
    }
}
