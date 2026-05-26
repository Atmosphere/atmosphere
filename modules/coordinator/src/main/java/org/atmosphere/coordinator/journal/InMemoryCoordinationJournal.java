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
package org.atmosphere.coordinator.journal;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * In-memory {@link CoordinationJournal} implementation. Thread-safe via
 * {@link ConcurrentHashMap} and {@link CopyOnWriteArrayList}.
 *
 * <p>Stores {@link EventEnvelope} natively so {@link #retrieveEnveloped(String)}
 * preserves causal lineage. Legacy {@link #record(CoordinationEvent)} callers
 * are wrapped as root envelopes with no parent link, matching the contract
 * documented on {@link CoordinationJournal#recordEnveloped(EventEnvelope)}.</p>
 *
 * <p>Enforces a maximum number of coordinations to prevent unbounded memory
 * growth. When the limit is exceeded, the oldest coordinations are evicted.</p>
 */
public final class InMemoryCoordinationJournal implements CoordinationJournal {

    private static final int DEFAULT_MAX_COORDINATIONS = 10_000;

    private final int maxCoordinations;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EventEnvelope>> store =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<CoordinationJournalInspector> inspectors =
            new CopyOnWriteArrayList<>();

    public InMemoryCoordinationJournal() {
        this(DEFAULT_MAX_COORDINATIONS);
    }

    public InMemoryCoordinationJournal(int maxCoordinations) {
        this.maxCoordinations = maxCoordinations;
    }

    @Override
    public void start() {
        // no-op for in-memory
    }

    @Override
    public void stop() {
        store.clear();
        insertionOrder.clear();
        inspectors.clear();
    }

    @Override
    public void record(CoordinationEvent event) {
        recordEnveloped(EventEnvelope.root(event));
    }

    @Override
    public String recordEnveloped(EventEnvelope envelope) {
        var event = envelope.event();
        for (var inspector : inspectors) {
            if (!inspector.shouldRecord(event)) {
                return envelope.eventId();
            }
        }
        store.computeIfAbsent(event.coordinationId(), k -> {
            insertionOrder.addLast(k);
            return new CopyOnWriteArrayList<>();
        }).add(envelope);
        evictIfNeeded();
        return envelope.eventId();
    }

    private void evictIfNeeded() {
        while (store.size() > maxCoordinations) {
            var oldest = insertionOrder.pollFirst();
            if (oldest != null) {
                store.remove(oldest);
            } else {
                break;
            }
        }
    }

    @Override
    public List<CoordinationEvent> retrieve(String coordinationId) {
        var envelopes = store.get(coordinationId);
        if (envelopes == null) {
            return List.of();
        }
        return envelopes.stream().map(EventEnvelope::event).toList();
    }

    @Override
    public List<EventEnvelope> retrieveEnveloped(String coordinationId) {
        var envelopes = store.get(coordinationId);
        return envelopes != null ? List.copyOf(envelopes) : List.of();
    }

    @Override
    public List<CoordinationEvent> query(CoordinationQuery query) {
        Stream<EventEnvelope> stream;

        if (query.coordinationId() != null) {
            var envelopes = store.get(query.coordinationId());
            stream = envelopes != null ? envelopes.stream() : Stream.empty();
        } else {
            stream = store.values().stream().flatMap(List::stream);
        }

        var events = stream.map(EventEnvelope::event);

        if (query.agentName() != null) {
            events = events.filter(e -> matchesAgent(e, query.agentName()));
        }
        if (query.since() != null) {
            events = events.filter(e -> !e.timestamp().isBefore(query.since()));
        }
        if (query.until() != null) {
            events = events.filter(e -> !e.timestamp().isAfter(query.until()));
        }
        if (query.limit() > 0) {
            events = events.limit(query.limit());
        }

        return events.toList();
    }

    @Override
    public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
        inspectors.add(inspector);
        return this;
    }

    private static boolean matchesAgent(CoordinationEvent event, String agentName) {
        return switch (event) {
            case CoordinationEvent.AgentDispatched e -> agentName.equals(e.agentName());
            case CoordinationEvent.AgentCompleted e -> agentName.equals(e.agentName());
            case CoordinationEvent.AgentFailed e -> agentName.equals(e.agentName());
            case CoordinationEvent.AgentEvaluated e -> agentName.equals(e.agentName());
            case CoordinationEvent.AgentHandoff e -> agentName.equals(e.fromAgent())
                    || agentName.equals(e.toAgent());
            case CoordinationEvent.RouteEvaluated e -> agentName.equals(e.inputAgent())
                    || agentName.equals(e.selectedAgent());
            case CoordinationEvent.AgentActivityChanged e -> agentName.equals(e.agentName());
            case CoordinationEvent.CircuitStateChanged e -> agentName.equals(e.agentName());
            case CoordinationEvent.CommitmentRecorded e ->
                    agentName.equals(e.record().subject());
            case CoordinationEvent.CoordinationStarted ignored -> false;
            case CoordinationEvent.CoordinationCompleted ignored -> false;
            case CoordinationEvent.ForkCreated ignored -> false;
        };
    }
}
