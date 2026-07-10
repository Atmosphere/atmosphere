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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeRun;
import org.atmosphere.ai.tape.TapeRunInfo;
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeStore;
import org.atmosphere.ai.tape.TapeSupport;
import org.atmosphere.quarkus.runtime.AtmosphereTapeProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the session-tape {@code @BuildStep} (Spring Boot parity for
 * {@code TapeInstaller}). Boots Quarkus with {@code atmosphere-ai} +
 * {@code atmosphere-checkpoint} and
 * {@code quarkus.atmosphere.ai.tape.enabled=true store=sqlite}, then proves
 * the same end-to-end chain the Spring tests pin: the
 * {@link AtmosphereTapeProducer} installed the recorder on
 * {@code StartupEvent}, the resolved store is the crash-durable SQLite store,
 * and a streamed turn driven through the real {@link TapeSupport#wrap} seam
 * lands as tape steps in a real {@code .db} file. The test would FAIL without
 * {@code AtmosphereProcessor.registerTapeProducer} because the holder would
 * stay uninstalled and nothing would be recorded.
 */
public class TapeBuildStepTest {

    // Deterministic path: QuarkusExtensionTest initializes this class in two
    // classloaders (build + runtime), so a time-/random-derived name would diverge
    // between the overrideConfigKey() call and the @Test body — leaving the store
    // at one path and the assertion checking another. A fixed name resolves to the
    // same file in both; a unique tapeId inside the @Test body keeps the
    // assertions blind to any runs a previous execution left in the file.
    private static final String DB_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "atmosphere-tape-quarkus-test.db").toString();

    static {
        new File(DB_PATH).deleteOnExit();
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    TapeBuildStepTest.class,
                    TapePlainSession.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.ai.tape.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.ai.tape.store", "sqlite")
            .overrideConfigKey("quarkus.atmosphere.ai.tape.path", DB_PATH);

    @Inject
    AtmosphereTapeProducer producer;

    @Test
    public void tapeRecorderInstalledAndPersistsAStreamedTurnOnDisk() throws Exception {
        assertNotNull(producer, "AtmosphereTapeProducer must be CDI-resolvable");
        assertTrue(producer.installed(),
                "Producer must have installed the tape recorder during StartupEvent");
        assertTrue(TapeSupport.installed(),
                "TapeSupport must report the recorder as installed");

        var store = TapeSupport.installedStore().orElseThrow();
        assertTrue(store.durable(),
                "store=sqlite + atmosphere-checkpoint present must resolve the crash-durable "
                        + "SQLite store, not the in-memory fallback");
        assertEquals("sqlite", store.name(), "the resolved store is the SQLite tape store");

        // Drive a turn through the same wrap seam the pipeline path reaches,
        // with the producer-installed recorder recording it. The unique tapeId
        // isolates this execution from runs a previous run left in the file.
        var clientId = "tape-client-" + UUID.randomUUID();
        var taped = TapeSupport.wrap(new TapePlainSession(),
                TapeRunInfo.pipeline(clientId, "model-x", "test-runtime"));
        taped.send("hello quarkus tape");
        taped.complete();

        var run = awaitCompletedRun(store, clientId);
        var steps = store.readSteps(run.runId(), 0, -1);
        assertTrue(steps.stream().anyMatch(s -> "text".equals(s.kind())
                        && s.payload().contains("hello quarkus tape")),
                "the streamed text landed as a taped step");
        assertTrue(steps.stream().anyMatch(s -> "complete".equals(s.kind())),
                "the completion landed as the terminal step");

        var db = Path.of(DB_PATH);
        assertTrue(Files.exists(db),
                "the tape was persisted to a real on-disk SQLite file at " + DB_PATH);
        assertTrue(Files.size(db) > 0L, "the database file holds data");
    }

    /**
     * The writer thread persists asynchronously; poll until this execution's
     * run reaches its COMPLETED terminal (bounded — the writer tick is
     * sub-second).
     */
    private static TapeRun awaitCompletedRun(TapeStore store, String tapeId)
            throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var completed = store.listRuns(new TapeQuery(tapeId, TapeStatus.COMPLETED, 10));
            if (!completed.isEmpty()) {
                return completed.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("tape run did not reach COMPLETED within 10s: "
                + store.listRuns(TapeQuery.byTapeId(tapeId, 10)));
    }
}

/** Minimal terminal-side {@link StreamingSession} the tape wraps. */
final class TapePlainSession implements StreamingSession {
    @Override
    public Optional<String> runId() {
        return Optional.empty();
    }

    @Override
    public void emit(AiEvent event) {
    }

    @Override
    public String sessionId() {
        return "session";
    }

    @Override
    public void send(String text) {
    }

    @Override
    public void sendMetadata(String key, Object value) {
    }

    @Override
    public void progress(String message) {
    }

    @Override
    public void complete() {
    }

    @Override
    public void complete(String summary) {
    }

    @Override
    public void error(Throwable t) {
    }

    @Override
    public boolean isClosed() {
        return false;
    }
}
