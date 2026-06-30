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
import org.atmosphere.ai.resume.DurableRunSpineHolder;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolExecutor;
import org.atmosphere.quarkus.runtime.AtmosphereDurableRunsProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the durable-runs {@code @BuildStep} (Spring Boot parity for
 * {@code DurableRunSpineInstaller}). Boots Quarkus with {@code atmosphere-ai} +
 * {@code atmosphere-checkpoint} and
 * {@code quarkus.atmosphere.durable-runs.enabled=true journal=sqlite}, then
 * proves the same end-to-end chain the Spring test pins: the
 * {@link AtmosphereDurableRunsProducer} installed the spine on
 * {@code StartupEvent}, the resolved journal is the crash-durable SQLite store,
 * and a tool call driven through the real cross-runtime
 * {@link ToolExecutionHelper} seam lands as a committed effect in a real
 * {@code .db} file. The test would FAIL without
 * {@code AtmosphereProcessor.registerDurableRunsProducer} because the holder
 * would stay at the disabled default and no effect would be journaled.
 */
public class DurableRunsBuildStepTest {

    // Deterministic path: QuarkusExtensionTest initializes this class in two
    // classloaders (build + runtime), so a time-/random-derived name would diverge
    // between the overrideConfigKey() call and the @Test body — leaving the journal
    // at one path and the assertion checking another. A fixed name resolves to the
    // same file in both.
    private static final String DB_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "atmosphere-durable-runs-quarkus-test.db").toString();

    static {
        new File(DB_PATH).deleteOnExit();
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    DurableRunsBuildStepTest.class,
                    CountingExecutor.class,
                    RunSession.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.durable-runs.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.durable-runs.journal", "sqlite")
            .overrideConfigKey("quarkus.atmosphere.durable-runs.path", DB_PATH);

    @Inject
    AtmosphereDurableRunsProducer producer;

    @Test
    public void durableRunSpineInstalledAndPersistsAToolEffectOnDisk() throws Exception {
        assertNotNull(producer, "AtmosphereDurableRunsProducer must be CDI-resolvable");
        assertTrue(producer.installed(),
                "Producer must have installed the durable-run spine during StartupEvent");

        var spine = DurableRunSpineHolder.get();
        assertTrue(spine.enabled(), "DurableRunSpineHolder must report an enabled spine");
        assertTrue(spine.journal().durable(),
                "journal=sqlite + atmosphere-checkpoint present must resolve the crash-durable "
                        + "SQLite journal, not the in-memory fallback");

        // Drive a tool call through the same cross-runtime seam a real run reaches,
        // with the producer-installed spine driving it.
        var runId = "run-quarkus-it";
        var ctx = spine.beginDrive(runId, "alice", "/chat").orElseThrow();
        var session = new RunSession(runId);
        var executor = new CountingExecutor();
        var echo = new ToolDefinition("echo", "echo", List.of(), "string", executor, null, 0);
        var result = ToolExecutionHelper.executeWithApproval(
                "echo", echo, Map.of("id", "v"), session, null, null, Map.of());

        assertEquals("ok", result, "the tool executes through the memoizing seam");
        var key = EffectKeys.toolCall(runId, "echo", Map.of("id", "v"), 0);
        assertTrue(spine.journal().lookupCommitted(runId, key).isPresent(),
                "the tool effect is committed to the journal");

        var db = Path.of(DB_PATH);
        assertTrue(Files.exists(db),
                "the effect was persisted to a real on-disk SQLite file at " + DB_PATH);
        assertTrue(Files.size(db) > 0L, "the database file holds data");

        spine.completeDrive(ctx, true);
    }
}

/** Test tool executor returning a fixed result. */
final class CountingExecutor implements ToolExecutor {
    @Override
    public Object execute(Map<String, Object> arguments) {
        return "ok";
    }
}

/** Minimal {@link StreamingSession} that reports a fixed run id. */
record RunSession(String id) implements StreamingSession {
    @Override
    public Optional<String> runId() {
        return Optional.of(id);
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
