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

import java.util.Objects;
import java.util.UUID;

/**
 * Identity + causal-lineage carrier for a {@link CoordinationEvent}. Separates
 * the wire/storage identity ({@link #eventId()}, {@link #parentEventId()}) from
 * the event payload so the existing {@code CoordinationEvent} record hierarchy
 * stays additive-compatible — callers that don't need lineage continue using
 * {@link CoordinationJournal#record(CoordinationEvent)} and
 * {@link CoordinationJournal#retrieve(String)}; callers that need to reconstruct
 * a causal DAG opt into {@link CoordinationJournal#recordEnveloped(EventEnvelope)}
 * and {@link CoordinationJournal#retrieveEnveloped(String)}.
 *
 * <p>{@link #parentEventId()} is {@code null} only for root events
 * ({@code CoordinationStarted} or any event emitted outside a started
 * coordination, e.g. a standalone {@code AgentDispatched} via
 * {@code JournalingAgentProxy}).</p>
 */
public record EventEnvelope(String eventId, String parentEventId, CoordinationEvent event) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(event, "event");
    }

    /** Root envelope (no parent). Generates a fresh eventId. */
    public static EventEnvelope root(CoordinationEvent event) {
        return new EventEnvelope(UUID.randomUUID().toString(), null, event);
    }

    /** Child envelope linked to {@code parentEventId}. Generates a fresh eventId. */
    public static EventEnvelope childOf(String parentEventId, CoordinationEvent event) {
        Objects.requireNonNull(parentEventId, "parentEventId");
        return new EventEnvelope(UUID.randomUUID().toString(), parentEventId, event);
    }

    /**
     * Wraps a bare event whose lineage is unknown (legacy {@code record(event)}
     * caller, or replay from a non-lineage-aware journal). The generated
     * eventId is stable for the lifetime of the envelope but not across calls.
     */
    public static EventEnvelope ofUnknownLineage(CoordinationEvent event) {
        return new EventEnvelope(UUID.randomUUID().toString(), null, event);
    }

    public boolean isRoot() {
        return parentEventId == null;
    }
}
