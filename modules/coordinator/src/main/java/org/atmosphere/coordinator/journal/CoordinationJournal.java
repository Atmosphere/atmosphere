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

/**
 * Pluggable coordination event journal. Records the execution graph of agent
 * coordinations for observability, debugging, and evaluation.
 *
 * <p>Follows the {@code BroadcasterCache} pattern: pluggable via ServiceLoader
 * with start/stop lifecycle, record/retrieve operations, and inspector hooks
 * for filtering.</p>
 */
public interface CoordinationJournal {

    /** Start the journal (acquire resources). */
    void start();

    /** Stop the journal (release resources). */
    void stop();

    /** Record a coordination event. Inspectors are consulted before storage. */
    void record(CoordinationEvent event);

    /** Retrieve all events for a specific coordination. */
    List<CoordinationEvent> retrieve(String coordinationId);

    /** Query events matching the given filter. */
    List<CoordinationEvent> query(CoordinationQuery query);

    /** Add an inspector that filters events before recording. */
    CoordinationJournal inspector(CoordinationJournalInspector inspector);

    /** Formats all events using {@link JournalFormat#STANDARD_LOG}. */
    default String formatLog() {
        return formatLog(JournalFormat.STANDARD_LOG);
    }

    /** Formats all events using the given format. */
    default String formatLog(JournalFormat format) {
        return formatLog(CoordinationQuery.all(), format);
    }

    /** Formats events matching the given query using the given format. */
    default String formatLog(CoordinationQuery filter, JournalFormat format) {
        return format.format(query(filter));
    }

    /** No-op journal that discards all events. */
    CoordinationJournal NOOP = new NoopCoordinationJournal();
}

/**
 * Package-private no-op implementation.
 */
final class NoopCoordinationJournal implements CoordinationJournal {

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void record(CoordinationEvent event) {}

    @Override
    public List<CoordinationEvent> retrieve(String coordinationId) {
        return List.of();
    }

    @Override
    public List<CoordinationEvent> query(CoordinationQuery query) {
        return List.of();
    }

    @Override
    public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
        return this;
    }
}
