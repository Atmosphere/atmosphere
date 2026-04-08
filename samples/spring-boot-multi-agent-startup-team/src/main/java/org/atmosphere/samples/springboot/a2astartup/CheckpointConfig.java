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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.checkpoint.SqliteCheckpointStore;
import org.atmosphere.checkpoint.coordinator.CheckpointingCoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationJournalInspector;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SQLite-backed coordination journal discoverable via ServiceLoader.
 * Wraps {@link InMemoryCoordinationJournal} with
 * {@link CheckpointingCoordinationJournal} for durable checkpoints.
 *
 * <p>Registered in
 * {@code META-INF/services/org.atmosphere.coordinator.journal.CoordinationJournal}.</p>
 */
public class CheckpointConfig implements CoordinationJournal {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointConfig.class);

    private final CheckpointingCoordinationJournal<CoordinationEvent> journal;
    private final SqliteCheckpointStore store;

    public CheckpointConfig() {
        store = new SqliteCheckpointStore();
        var delegate = new InMemoryCoordinationJournal();
        journal = new CheckpointingCoordinationJournal<>(
                delegate, store,
                CheckpointingCoordinationJournal.onAgentBoundaries(),
                event -> event);
    }

    @Override
    public void start() {
        store.start();
        journal.start();
        logger.info("Coordination journal with SQLite checkpoints initialized");
    }

    @Override
    public void stop() {
        journal.stop();
        store.stop();
    }

    @Override
    public void record(CoordinationEvent event) {
        journal.record(event);
    }

    @Override
    public List<CoordinationEvent> retrieve(String coordinationId) {
        return journal.retrieve(coordinationId);
    }

    @Override
    public List<CoordinationEvent> query(CoordinationQuery query) {
        return journal.query(query);
    }

    @Override
    public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
        return journal.inspector(inspector);
    }
}
