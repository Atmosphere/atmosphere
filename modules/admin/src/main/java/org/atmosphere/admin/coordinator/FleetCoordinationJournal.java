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
package org.atmosphere.admin.coordinator;

import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationJournalInspector;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.coordinator.journal.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read-only view over the journals the registered coordinator fleets actually
 * record into. {@code CoordinatorProcessor} resolves each coordinator's journal
 * itself (framework property, then {@code ServiceLoader}) and keeps it on the
 * {@link AgentFleet}; reading it back through {@link AgentFleet#journal()} on
 * every call means the admin endpoints report the events that exist, whichever
 * way the journal was provided (Correctness Invariant #5, Runtime Truth).
 *
 * <p>The view does not own those journals: {@link #start()} and {@link #stop()}
 * do nothing and {@link #record(CoordinationEvent)} is rejected (Correctness
 * Invariant #1, Ownership). One distinct journal is passed through unchanged;
 * several are merged in timestamp order, with the query limit applied to the
 * merged result.</p>
 */
public final class FleetCoordinationJournal implements CoordinationJournal {

    private static final Logger logger = LoggerFactory.getLogger(FleetCoordinationJournal.class);

    private final Supplier<Map<String, AgentFleet>> fleetsSupplier;
    private final CoordinationJournal fallback;

    /**
     * @param fleetsSupplier live view of the registered fleets, read on every call
     * @param fallback       an application-provided journal to include as well
     *                       (may be {@link CoordinationJournal#NOOP})
     */
    public FleetCoordinationJournal(Supplier<Map<String, AgentFleet>> fleetsSupplier,
                                    CoordinationJournal fallback) {
        this.fleetsSupplier = fleetsSupplier != null ? fleetsSupplier : Map::of;
        this.fallback = fallback != null ? fallback : CoordinationJournal.NOOP;
    }

    /** The distinct live journals, NOOP excluded, deduplicated by identity. */
    List<CoordinationJournal> journals() {
        Set<CoordinationJournal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var result = new ArrayList<CoordinationJournal>();
        Map<String, AgentFleet> fleets;
        try {
            fleets = fleetsSupplier.get();
        } catch (RuntimeException e) {
            logger.debug("Coordinator fleets not available; reading the fallback journal only", e);
            fleets = Map.of();
        }
        if (fleets != null) {
            for (var fleet : fleets.values()) {
                if (fleet != null) {
                    add(fleet.journal(), seen, result);
                }
            }
        }
        add(fallback, seen, result);
        return result;
    }

    private static void add(CoordinationJournal journal, Set<CoordinationJournal> seen,
                            List<CoordinationJournal> result) {
        if (journal != null && journal != CoordinationJournal.NOOP && seen.add(journal)) {
            result.add(journal);
        }
    }

    @Override
    public void start() {
        // The fleets' journals are started and stopped by their coordinators.
    }

    @Override
    public void stop() {
        // The fleets' journals are started and stopped by their coordinators.
    }

    @Override
    public void record(CoordinationEvent event) {
        throw new UnsupportedOperationException(
                "FleetCoordinationJournal is a read-only view; record through the coordinator's journal");
    }

    @Override
    public List<CoordinationEvent> retrieve(String coordinationId) {
        return merge(j -> j.retrieve(coordinationId), CoordinationEvent::timestamp, 0);
    }

    @Override
    public List<EventEnvelope> retrieveEnveloped(String coordinationId) {
        return merge(j -> j.retrieveEnveloped(coordinationId), e -> e.event().timestamp(), 0);
    }

    @Override
    public List<CoordinationEvent> query(CoordinationQuery query) {
        return merge(j -> j.query(query), CoordinationEvent::timestamp, query.limit());
    }

    @Override
    public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
        // Inspectors gate writes; this view never writes, so there is nothing to attach.
        return this;
    }

    private <T> List<T> merge(Function<CoordinationJournal, List<T>> read,
                              Function<T, Instant> timestamp, int limit) {
        var journals = journals();
        if (journals.isEmpty()) {
            return List.of();
        }
        if (journals.size() == 1) {
            return read.apply(journals.get(0));
        }
        var merged = new ArrayList<T>();
        for (var journal : journals) {
            merged.addAll(read.apply(journal));
        }
        merged.sort(Comparator.comparing(timestamp));
        if (limit > 0 && merged.size() > limit) {
            return List.copyOf(merged.subList(0, limit));
        }
        return List.copyOf(merged);
    }
}
