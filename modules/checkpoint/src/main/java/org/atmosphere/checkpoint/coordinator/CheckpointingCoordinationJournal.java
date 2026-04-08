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
package org.atmosphere.checkpoint.coordinator;

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationJournalInspector;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.coordinator.journal.JournalFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Decorator that delegates to an underlying {@link CoordinationJournal} while
 * also persisting a {@link WorkflowSnapshot} to a {@link CheckpointStore}
 * whenever a recorded event matches a user-supplied filter.
 *
 * <p>This is the bridge between the Atmosphere coordinator's event stream and
 * the checkpoint SPI: it turns the coordinator's <em>observability</em>
 * journal into a <em>durable</em> execution log without requiring any changes
 * to the coordinator module itself.</p>
 *
 * <p>Snapshots form a chain per coordination: the first snapshot for a
 * coordination is a root; each subsequent snapshot references the previous
 * one as its parent. This mirrors the event ordering in the underlying
 * journal.</p>
 *
 * <p>Wiring example:
 * <pre>{@code
 * CheckpointStore store = new InMemoryCheckpointStore();
 * CoordinationJournal journal = new CheckpointingCoordinationJournal(
 *         new InMemoryCoordinationJournal(),
 *         store,
 *         CheckpointingCoordinationJournal.onAgentBoundaries(),
 *         CoordinationStateExtractors.eventSummary());
 * }</pre>
 * </p>
 *
 * <p>Only the <em>saving</em> of snapshots is automated here; loading, forking
 * and replay are left to the application, since the meaning of "resume" is
 * workflow-specific.</p>
 *
 * @param <S> application-owned workflow state type
 */
public final class CheckpointingCoordinationJournal<S> implements CoordinationJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointingCoordinationJournal.class);

    private final CoordinationJournal delegate;
    private final CheckpointStore store;
    private final Predicate<CoordinationEvent> snapshotFilter;
    private final CoordinationStateExtractor<S> extractor;
    private final ConcurrentHashMap<String, CheckpointId> lastSnapshotPerCoordination = new ConcurrentHashMap<>();

    public CheckpointingCoordinationJournal(
            CoordinationJournal delegate,
            CheckpointStore store,
            Predicate<CoordinationEvent> snapshotFilter,
            CoordinationStateExtractor<S> extractor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.snapshotFilter = Objects.requireNonNull(snapshotFilter, "snapshotFilter must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
    }

    /** Snapshot filter matching every {@code AgentCompleted} or {@code AgentFailed} event. */
    public static Predicate<CoordinationEvent> onAgentBoundaries() {
        return event -> event instanceof CoordinationEvent.AgentCompleted
                || event instanceof CoordinationEvent.AgentFailed;
    }

    /** Snapshot filter matching every recorded event. */
    public static Predicate<CoordinationEvent> onEveryEvent() {
        return event -> true;
    }

    @Override
    public void start() {
        delegate.start();
        store.start();
    }

    @Override
    public void stop() {
        delegate.stop();
        store.stop();
        lastSnapshotPerCoordination.clear();
    }

    @Override
    public void record(CoordinationEvent event) {
        delegate.record(event);
        if (!snapshotFilter.test(event)) {
            return;
        }
        try {
            var coordinationId = event.coordinationId();
            var parentId = lastSnapshotPerCoordination.get(coordinationId);
            var snapshot = WorkflowSnapshot.<S>builder()
                    .id(CheckpointId.random())
                    .parentId(parentId)
                    .coordinationId(coordinationId)
                    .agentName(agentNameFor(event))
                    .state(extractor.extract(event))
                    .createdAt(event.timestamp())
                    .build();
            store.save(snapshot);
            lastSnapshotPerCoordination.put(coordinationId, snapshot.id());
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to persist checkpoint for event {}", event, ex);
        }
    }

    @Override
    public List<CoordinationEvent> retrieve(String coordinationId) {
        return delegate.retrieve(coordinationId);
    }

    @Override
    public List<CoordinationEvent> query(CoordinationQuery query) {
        return delegate.query(query);
    }

    @Override
    public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
        delegate.inspector(inspector);
        return this;
    }

    @Override
    public String formatLog(CoordinationQuery filter, JournalFormat format) {
        return delegate.formatLog(filter, format);
    }

    /** The most recent snapshot id written for {@code coordinationId}, if any. */
    public CheckpointId lastSnapshot(String coordinationId) {
        return lastSnapshotPerCoordination.get(coordinationId);
    }

    private static String agentNameFor(CoordinationEvent event) {
        return switch (event) {
            case CoordinationEvent.AgentDispatched e -> e.agentName();
            case CoordinationEvent.AgentCompleted e -> e.agentName();
            case CoordinationEvent.AgentFailed e -> e.agentName();
            case CoordinationEvent.AgentEvaluated e -> e.agentName();
            case CoordinationEvent.AgentHandoff e -> e.toAgent();
            case CoordinationEvent.RouteEvaluated e -> e.selectedAgent();
            case CoordinationEvent.AgentActivityChanged e -> e.agentName();
            case CoordinationEvent.CoordinationStarted e -> e.coordinatorName();
            case CoordinationEvent.CoordinationCompleted ignored -> null;
        };
    }
}
