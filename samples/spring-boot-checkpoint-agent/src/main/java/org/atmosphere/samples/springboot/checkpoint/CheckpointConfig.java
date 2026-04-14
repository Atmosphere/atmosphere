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
package org.atmosphere.samples.springboot.checkpoint;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.SqliteCheckpointStore;
import org.atmosphere.checkpoint.coordinator.CheckpointingCoordinationJournal;
import org.atmosphere.checkpoint.coordinator.CoordinationStateExtractor;
import org.atmosphere.checkpoint.coordinator.CoordinationStateExtractors;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires a {@link CheckpointStore} and decorates the coordinator's
 * {@link CoordinationJournal} with {@link CheckpointingCoordinationJournal}
 * so every agent boundary emits a durable snapshot.
 *
 * <p>By default the sample uses a {@link SqliteCheckpointStore} backed by a
 * local {@code target/checkpoint.db} file — that way {@code mvn clean} is
 * enough to reset the store, but a JVM restart (without {@code mvn clean})
 * preserves all snapshots and demonstrates durable HITL survival across
 * restarts. Set {@code atmosphere.checkpoint.store=in-memory} to opt out
 * for tests that want a clean slate on each boot.</p>
 *
 * <p>Configuration (see {@code application.yml}):</p>
 * <pre>
 *   atmosphere.checkpoint.store        sqlite|in-memory (default: sqlite)
 *   atmosphere.checkpoint.sqlite.path  filesystem path to the SQLite db
 *                                      (default: target/checkpoint.db)
 * </pre>
 */
@Configuration
public class CheckpointConfig {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointConfig.class);

    static final String STORE_SQLITE = "sqlite";
    static final String STORE_IN_MEMORY = "in-memory";

    private final String storeKind;
    private final String sqlitePath;

    public CheckpointConfig(
            @Value("${atmosphere.checkpoint.store:sqlite}") String storeKind,
            @Value("${atmosphere.checkpoint.sqlite.path:target/checkpoint.db}") String sqlitePath) {
        this.storeKind = storeKind == null ? STORE_SQLITE : storeKind.trim().toLowerCase();
        this.sqlitePath = sqlitePath == null ? "target/checkpoint.db" : sqlitePath;
    }

    /**
     * CheckpointStore bean. {@code stop()} is bound as the destroy method so
     * the Spring context closes the SQLite connection on shutdown; without
     * this, the JDBC connection leaks on every restart.
     */
    @Bean(destroyMethod = "stop")
    public CheckpointStore checkpointStore() {
        CheckpointStore store = switch (storeKind) {
            case STORE_IN_MEMORY -> {
                logger.info("Using InMemoryCheckpointStore — snapshots will be lost on restart.");
                yield new InMemoryCheckpointStore();
            }
            case STORE_SQLITE -> {
                Path dbPath = Paths.get(sqlitePath).toAbsolutePath();
                logger.info("Using SqliteCheckpointStore at {} — snapshots survive JVM restart.", dbPath);
                yield new SqliteCheckpointStore(dbPath);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown atmosphere.checkpoint.store value '" + storeKind
                            + "'. Valid values: " + STORE_SQLITE + ", " + STORE_IN_MEMORY);
        };
        store.start();
        return store;
    }

    @Bean
    @Primary
    public CoordinationJournal coordinationJournal(CheckpointStore store) {
        var underlying = new InMemoryCoordinationJournal();
        CoordinationStateExtractor<CoordinationEvent> extractor =
                CoordinationStateExtractors.event();
        // Do NOT call journal.start() here. CheckpointingCoordinationJournal.start()
        // delegates to store.start(), which would re-initialize the SQLite schema
        // and emit a duplicate "SqliteCheckpointStore initialized" log line —
        // checkpointStore() above has already started the store. The processor's
        // bridged-journal path in CoordinatorProcessor also skips start() because
        // this bean is Spring-owned (see AtmosphereCoordinatorAutoConfiguration).
        return journal(underlying, store, extractor);
    }

    private static CoordinationJournal journal(
            InMemoryCoordinationJournal underlying,
            CheckpointStore store,
            CoordinationStateExtractor<CoordinationEvent> extractor) {
        return new CheckpointingCoordinationJournal<>(
                underlying,
                store,
                CheckpointingCoordinationJournal.onAgentBoundaries(),
                extractor);
    }
}
