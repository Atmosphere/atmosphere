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
package org.atmosphere.integrationtests.ai;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.ai.episodicmemory.EpisodicMemoryQuery;
import org.atmosphere.ai.episodicmemory.EpisodicMemoryStore;
import org.atmosphere.ai.episodicmemory.EpisodicMemoryType;
import org.atmosphere.ai.episodicmemory.MemoryEntry;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives the {@link EpisodicMemoryStore} SPI end-to-end through a live
 * server session.
 *
 * <p>Prompt routing:</p>
 * <ul>
 *   <li>{@code roundtrip} — exercises store + recall + forget against an
 *       in-memory store, captures JFR EpisodicMemoryAccess events,
 *       and reports counts as websocket metadata.</li>
 *   <li>{@code persist}   — writes three entries to a temp
 *       JsonFileEpisodicMemoryStore, drops the store instance, opens a
 *       fresh one against the same path, and reports the recall size to
 *       prove on-disk persistence.</li>
 * </ul>
 */
public class EpisodicMemoryTestHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(EpisodicMemoryTestHandler.class);

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var prompt = resource.getRequest().getReader().readLine();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        Thread.ofVirtual().name("episodic-memory-test")
                .start(() -> handlePrompt(resource, prompt.trim()));
    }

    private void handlePrompt(AtmosphereResource resource, String mode) {
        var session = StreamingSessions.start(resource);
        try {
            switch (mode.toLowerCase()) {
                case "roundtrip" -> runRoundTrip(session);
                case "persist" -> runPersist(session);
                default -> session.error(new IllegalArgumentException(
                        "Unknown mode: " + mode + " (expected roundtrip or persist)"));
            }
            session.complete();
        } catch (Exception e) {
            logger.error("EpisodicMemory e2e handler failed", e);
            session.error(e);
        }
    }

    private void runRoundTrip(org.atmosphere.ai.StreamingSession session) throws IOException {
        Path dump = null;
        try (var recording = new Recording()) {
            recording.enable(org.atmosphere.ai.jfr.EpisodicMemoryAccessEvent.class);
            recording.start();

            var store = EpisodicMemoryStore.inMemory();
            var first = MemoryEntry.of(EpisodicMemoryType.USER, "ChefFamille writes Java");
            store.store(first);
            store.store(MemoryEntry.of(EpisodicMemoryType.FEEDBACK, "Never use --no-verify"));
            store.store(MemoryEntry.of(EpisodicMemoryType.PROJECT, "Atmosphere coordinator GA"));
            int sizeAfterStore = store.size();

            var feedback = store.recall(
                    EpisodicMemoryQuery.ofType(EpisodicMemoryType.FEEDBACK, 5));
            var allRecent = store.recall(EpisodicMemoryQuery.recent(10));
            var forgot = store.forget(first.id());
            int sizeAfterForget = store.size();

            recording.stop();
            dump = Files.createTempFile("atmosphere-mem-e2e-", ".jfr");
            recording.dump(dump);

            session.sendMetadata("ai.memory.roundtrip.size.afterStore", sizeAfterStore);
            session.sendMetadata("ai.memory.roundtrip.feedback.count", feedback.size());
            session.sendMetadata("ai.memory.roundtrip.recent.count", allRecent.size());
            session.sendMetadata("ai.memory.roundtrip.forgot", forgot);
            session.sendMetadata("ai.memory.roundtrip.size.afterForget", sizeAfterForget);

            emitJfrBreakdown(session, dump, "roundtrip");
        } finally {
            if (dump != null) {
                Files.deleteIfExists(dump);
            }
        }
    }

    private void runPersist(org.atmosphere.ai.StreamingSession session) throws IOException {
        Path dump = null;
        Path memoryFile = null;
        try (var recording = new Recording()) {
            recording.enable(org.atmosphere.ai.jfr.EpisodicMemoryAccessEvent.class);
            recording.start();

            memoryFile = Files.createTempFile("atmosphere-mem-e2e-", ".json");
            // Start clean — createTempFile gives us a zero-byte file which
            // JsonFileEpisodicMemoryStore treats as "empty", but we delete
            // anyway so the missing-file load path is also exercised.
            Files.deleteIfExists(memoryFile);

            var writer = EpisodicMemoryStore.jsonFile(memoryFile);
            writer.store(MemoryEntry.of(EpisodicMemoryType.USER, "first"));
            writer.store(MemoryEntry.of(EpisodicMemoryType.FEEDBACK, "second"));
            writer.store(MemoryEntry.of(EpisodicMemoryType.PROJECT, "third"));
            var writerSize = writer.size();
            var fileSize = Files.size(memoryFile);

            // New instance, same path — proves on-disk persistence.
            var reader = EpisodicMemoryStore.jsonFile(memoryFile);
            var recalled = reader.recall(EpisodicMemoryQuery.recent(10));

            recording.stop();
            dump = Files.createTempFile("atmosphere-mem-e2e-jfr-", ".jfr");
            recording.dump(dump);

            session.sendMetadata("ai.memory.persist.writer.size", writerSize);
            session.sendMetadata("ai.memory.persist.file.bytes", fileSize);
            session.sendMetadata("ai.memory.persist.reader.size", reader.size());
            session.sendMetadata("ai.memory.persist.recall.count", recalled.size());

            emitJfrBreakdown(session, dump, "persist");
        } finally {
            if (dump != null) {
                Files.deleteIfExists(dump);
            }
            if (memoryFile != null) {
                Files.deleteIfExists(memoryFile);
            }
        }
    }

    private static void emitJfrBreakdown(org.atmosphere.ai.StreamingSession session,
                                         Path dump, String scope) {
        int store = 0;
        int recall = 0;
        int forget = 0;
        int total = 0;
        String diagnostic = "ok";
        long dumpBytes = -1;
        var observedNames = new java.util.LinkedHashMap<String, Integer>();
        try {
            dumpBytes = Files.size(dump);
            try (var file = new RecordingFile(dump)) {
                while (file.hasMoreEvents()) {
                    var event = file.readEvent();
                    total++;
                    var name = event.getEventType().getName();
                    observedNames.merge(name, 1, Integer::sum);
                    if (!"org.atmosphere.ai.EpisodicMemoryAccess".equals(name)) {
                        continue;
                    }
                    var operation = event.getString("operation");
                    switch (operation == null ? "" : operation) {
                        case "STORE" -> store++;
                        case "RECALL" -> recall++;
                        case "FORGET" -> forget++;
                        default -> {
                            // ignore unknown
                        }
                    }
                }
            }
        } catch (Exception e) {
            diagnostic = e.getClass().getSimpleName() + ": " + e.getMessage();
            logger.warn("emitJfrBreakdown failed for scope {}", scope, e);
        }
        session.sendMetadata("ai.memory." + scope + ".jfr.dumpBytes", dumpBytes);
        session.sendMetadata("ai.memory." + scope + ".jfr.diagnostic", diagnostic);
        session.sendMetadata("ai.memory." + scope + ".jfr.observedNames",
                observedNames.toString());
        session.sendMetadata("ai.memory." + scope + ".jfr.totalEvents", total);
        session.sendMetadata("ai.memory." + scope + ".jfr.store", store);
        session.sendMetadata("ai.memory." + scope + ".jfr.recall", recall);
        session.sendMetadata("ai.memory." + scope + ".jfr.forget", forget);
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }
}
