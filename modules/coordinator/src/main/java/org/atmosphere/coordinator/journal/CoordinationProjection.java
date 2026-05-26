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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure causal-DAG projection of one coordination's journal. Built from
 * {@link CoordinationJournal#retrieveEnveloped(String)}, it walks the
 * {@code parentEventId} edges to expose roots, children, and depth-first
 * traversal — no execution, no LLM, no side effects.
 *
 * <p>The projection is a read-only snapshot taken at construction time:
 * subsequent journal writes are NOT reflected. Call {@link #from} again to
 * pick up new events.</p>
 */
public final class CoordinationProjection {

    private final String coordinationId;
    private final List<EventEnvelope> envelopes;
    private final Map<String, EventEnvelope> byId;
    private final Map<String, List<EventEnvelope>> childrenByParent;
    private final List<EventEnvelope> roots;

    private CoordinationProjection(String coordinationId, List<EventEnvelope> source) {
        this.coordinationId = Objects.requireNonNull(coordinationId, "coordinationId");
        this.envelopes = List.copyOf(source);

        var index = new LinkedHashMap<String, EventEnvelope>();
        var children = new LinkedHashMap<String, List<EventEnvelope>>();
        var rootList = new ArrayList<EventEnvelope>();

        for (var env : envelopes) {
            index.put(env.eventId(), env);
            if (env.isRoot()) {
                rootList.add(env);
            } else {
                children.computeIfAbsent(env.parentEventId(), k -> new ArrayList<>()).add(env);
            }
        }

        this.byId = Map.copyOf(index);
        // Freeze child lists too so callers can't mutate the projection.
        var frozenChildren = new LinkedHashMap<String, List<EventEnvelope>>(children.size());
        children.forEach((parent, kids) -> frozenChildren.put(parent, List.copyOf(kids)));
        this.childrenByParent = Map.copyOf(frozenChildren);
        this.roots = List.copyOf(rootList);
    }

    /**
     * Build a projection for {@code coordinationId} from the lineage-aware
     * journal API. Journals that don't preserve lineage (legacy default impl)
     * will yield a projection where every event is a root.
     */
    public static CoordinationProjection from(CoordinationJournal journal, String coordinationId) {
        Objects.requireNonNull(journal, "journal");
        return new CoordinationProjection(coordinationId, journal.retrieveEnveloped(coordinationId));
    }

    public String coordinationId() {
        return coordinationId;
    }

    /** All envelopes in journal order. */
    public List<EventEnvelope> envelopes() {
        return envelopes;
    }

    /** Envelopes with no parent (entry points of the DAG). */
    public List<EventEnvelope> roots() {
        return roots;
    }

    /** Lookup an envelope by its {@code eventId}. */
    public Optional<EventEnvelope> event(String eventId) {
        return Optional.ofNullable(byId.get(eventId));
    }

    /** Direct children of {@code eventId} (empty list if leaf or unknown). */
    public List<EventEnvelope> children(String eventId) {
        return childrenByParent.getOrDefault(eventId, List.of());
    }

    /**
     * Depth-first traversal from every root. Visitor receives each envelope and
     * its depth (0 for roots). Sibling order follows journal insertion order.
     */
    public void walk(Visitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        for (var root : roots) {
            walkFrom(root, 0, visitor);
        }
    }

    private void walkFrom(EventEnvelope env, int depth, Visitor visitor) {
        visitor.visit(env, depth);
        for (var child : children(env.eventId())) {
            walkFrom(child, depth + 1, visitor);
        }
    }

    /** All distinct agent names participating in this coordination, in first-seen order. */
    public Set<String> agents() {
        var names = new LinkedHashSet<String>();
        for (var env : envelopes) {
            var name = agentNameOf(env.event());
            if (name != null) {
                names.add(name);
            }
        }
        return Set.copyOf(names);
    }

    /** All {@code AgentFailed} envelopes in journal order. */
    public List<EventEnvelope> failedDispatches() {
        return envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentFailed)
                .toList();
    }

    /** All {@code AgentEvaluated} envelopes in journal order. */
    public List<EventEnvelope> evaluations() {
        return envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentEvaluated)
                .toList();
    }

    private static String agentNameOf(CoordinationEvent event) {
        return switch (event) {
            case CoordinationEvent.AgentDispatched e -> e.agentName();
            case CoordinationEvent.AgentCompleted e -> e.agentName();
            case CoordinationEvent.AgentFailed e -> e.agentName();
            case CoordinationEvent.AgentEvaluated e -> e.agentName();
            case CoordinationEvent.AgentActivityChanged e -> e.agentName();
            case CoordinationEvent.CircuitStateChanged e -> e.agentName();
            case CoordinationEvent.AgentHandoff e -> e.toAgent();
            case CoordinationEvent.RouteEvaluated e -> e.selectedAgent();
            case CoordinationEvent.CommitmentRecorded e -> e.record().subject();
            case CoordinationEvent.CoordinationStarted ignored -> null;
            case CoordinationEvent.CoordinationCompleted ignored -> null;
            case CoordinationEvent.ForkCreated ignored -> null;
        };
    }

    /** Visitor for {@link #walk(Visitor)} — receives each envelope with its DAG depth. */
    @FunctionalInterface
    public interface Visitor {
        void visit(EventEnvelope envelope, int depth);
    }
}
