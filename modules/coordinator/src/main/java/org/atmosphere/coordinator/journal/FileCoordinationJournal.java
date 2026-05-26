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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only NDJSON file-backed {@link CoordinationJournal}. One JSON object
 * per line — survives JVM restart by replaying the file into an in-memory
 * index on {@link #start()}.
 *
 * <p>Concurrency: a single writer lock serializes appends; reads go to the
 * in-memory index without locking. Crash safety: a malformed (e.g. truncated)
 * final line on replay is logged and skipped, so a JVM kill mid-write does not
 * brick the journal.</p>
 *
 * <p>Polymorphic ser/deser of the sealed {@link CoordinationEvent} hierarchy
 * is configured via a Jackson mix-in registered on the local {@link ObjectMapper}
 * — the {@code CoordinationEvent} records themselves stay Jackson-annotation-free
 * so non-persistence consumers don't pay the dep.</p>
 */
public final class FileCoordinationJournal implements CoordinationJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileCoordinationJournal.class);

    private final Path file;
    private final InMemoryCoordinationJournal index;
    private final ObjectMapper mapper;
    private final CopyOnWriteArrayList<CoordinationJournalInspector> inspectors =
            new CopyOnWriteArrayList<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    private BufferedWriter writer;
    private volatile boolean started;

    public FileCoordinationJournal(Path file) {
        this(file, new InMemoryCoordinationJournal());
    }

    public FileCoordinationJournal(Path file, InMemoryCoordinationJournal index) {
        this.file = Objects.requireNonNull(file, "file");
        this.index = Objects.requireNonNull(index, "index");
        this.mapper = buildMapper();
    }

    static ObjectMapper buildMapper() {
        return JsonMapper.builder()
                .addMixIn(CoordinationEvent.class, CoordinationEventMixIn.class)
                .build();
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        writeLock.lock();
        try {
            var parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            index.start();
            if (Files.exists(file)) {
                replayInto(index);
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            started = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open journal " + file, e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void stop() {
        writeLock.lock();
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
            started = false;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close journal " + file, e);
        } finally {
            writeLock.unlock();
        }
        // index.stop() must run after the writer is closed, since stop() clears
        // the in-memory store; if a concurrent persist won the lock it would
        // race against the cleared store.
        index.stop();
    }

    @Override
    public void record(CoordinationEvent event) {
        recordEnveloped(EventEnvelope.root(event));
    }

    @Override
    public String recordEnveloped(EventEnvelope envelope) {
        for (var inspector : inspectors) {
            if (!inspector.shouldRecord(envelope.event())) {
                return envelope.eventId();
            }
        }
        persist(envelope);
        // index has no inspectors of its own (we own the inspector chain), so
        // recordEnveloped accepts unconditionally.
        index.recordEnveloped(envelope);
        return envelope.eventId();
    }

    private void persist(EventEnvelope envelope) {
        writeLock.lock();
        try {
            if (writer == null) {
                throw new IllegalStateException("FileCoordinationJournal is not started");
            }
            writer.write(mapper.writeValueAsString(envelope));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to journal " + file, e);
        } finally {
            writeLock.unlock();
        }
    }

    private void replayInto(InMemoryCoordinationJournal target) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isEmpty()) {
                    return;
                }
                try {
                    var envelope = mapper.readValue(line, EventEnvelope.class);
                    target.recordEnveloped(envelope);
                } catch (RuntimeException e) {
                    // Truncated or malformed line — typical after a JVM kill
                    // mid-append. Log and skip per the crash-safety contract.
                    LOGGER.warn("Skipping malformed journal line in {}: {}",
                            file, e.getMessage());
                }
            });
        }
    }

    @Override
    public List<CoordinationEvent> retrieve(String coordinationId) {
        return index.retrieve(coordinationId);
    }

    @Override
    public List<EventEnvelope> retrieveEnveloped(String coordinationId) {
        return index.retrieveEnveloped(coordinationId);
    }

    @Override
    public List<CoordinationEvent> query(CoordinationQuery query) {
        return index.query(query);
    }

    @Override
    public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
        inspectors.add(inspector);
        return this;
    }

    /**
     * Jackson mix-in that registers the sealed {@link CoordinationEvent}
     * hierarchy for polymorphic ser/deser without modifying the records
     * themselves. The {@code @type} property names each subtype so NDJSON
     * lines stay self-describing.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = CoordinationEvent.CoordinationStarted.class, name = "CoordinationStarted"),
            @JsonSubTypes.Type(value = CoordinationEvent.AgentDispatched.class, name = "AgentDispatched"),
            @JsonSubTypes.Type(value = CoordinationEvent.AgentCompleted.class, name = "AgentCompleted"),
            @JsonSubTypes.Type(value = CoordinationEvent.AgentFailed.class, name = "AgentFailed"),
            @JsonSubTypes.Type(value = CoordinationEvent.AgentEvaluated.class, name = "AgentEvaluated"),
            @JsonSubTypes.Type(value = CoordinationEvent.AgentHandoff.class, name = "AgentHandoff"),
            @JsonSubTypes.Type(value = CoordinationEvent.RouteEvaluated.class, name = "RouteEvaluated"),
            @JsonSubTypes.Type(value = CoordinationEvent.CoordinationCompleted.class, name = "CoordinationCompleted"),
            @JsonSubTypes.Type(value = CoordinationEvent.AgentActivityChanged.class, name = "AgentActivityChanged"),
            @JsonSubTypes.Type(value = CoordinationEvent.CircuitStateChanged.class, name = "CircuitStateChanged"),
            @JsonSubTypes.Type(value = CoordinationEvent.CommitmentRecorded.class, name = "CommitmentRecorded"),
            @JsonSubTypes.Type(value = CoordinationEvent.ForkCreated.class, name = "ForkCreated")
    })
    private abstract static class CoordinationEventMixIn {
    }
}
