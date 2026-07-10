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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tape.InMemoryTapeStore;
import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeRunInfo;
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeStore;
import org.atmosphere.ai.tape.TapeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 3 parity for the session-tape enablement wiring, pinning the
 * full on-disk chain <em>TapeSupport.wrap → TapeRecordingSession →
 * TapeRecorder writer → SqliteTapeStore persists on disk</em>: off by default,
 * opt-in installs a recorder, and with the bundled SQLite store a streamed
 * turn driven through the real wrap seam lands as tape steps in a real
 * {@code .db} file. Mirrors the SB4 starter's {@code TapeAutoConfigurationTest}.
 */
class TapeAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    @Test
    void offByDefaultInstallsNoRecorder() {
        contextRunner.run(context -> {
            assertThat(context)
                    .doesNotHaveBean(AtmosphereAiAutoConfiguration.TapeInstaller.class);
            assertThat(TapeSupport.installed())
                    .as("no opt-in means no recorder is installed")
                    .isFalse();
        });
    }

    @Test
    void enabledWithSqliteStorePersistsAStreamedTurnOnDisk(@TempDir Path tmp) {
        var db = tmp.resolve("tape.db");
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.tape.enabled=true",
                        "atmosphere.ai.tape.store=sqlite",
                        "atmosphere.ai.tape.path=" + db)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(AtmosphereAiAutoConfiguration.TapeInstaller.class);
                    assertThat(TapeSupport.installed()).isTrue();
                    var store = TapeSupport.installedStore().orElseThrow();
                    assertThat(store.durable())
                            .as("the bundled SQLite tape store is crash-durable")
                            .isTrue();
                    assertThat(store.name()).isEqualTo("sqlite");

                    // Drive a turn through the same wrap seam the pipeline path
                    // reaches, with the autoconfig-installed recorder recording it.
                    var taped = TapeSupport.wrap(new PlainSession(),
                            TapeRunInfo.pipeline("client-tape", "model-x", "test-runtime"));
                    taped.send("hello tape");
                    taped.complete();

                    var run = awaitCompletedRun(store);
                    var steps = store.readSteps(run.runId(), 0, -1);
                    assertThat(steps)
                            .as("the streamed text landed as a taped step")
                            .anyMatch(s -> "text".equals(s.kind())
                                    && s.payload().contains("hello tape"));
                    assertThat(steps)
                            .as("the completion landed as the terminal step")
                            .anyMatch(s -> "complete".equals(s.kind()));
                    assertThat(Files.exists(db))
                            .as("the tape was persisted to a real on-disk SQLite file")
                            .isTrue();
                    assertThat(db.toFile().length())
                            .as("the database file holds data")
                            .isGreaterThan(0L);
                });
        assertThat(TapeSupport.installed())
                .as("context close uninstalls the recorder (Invariant #1 symmetry)")
                .isFalse();
    }

    @Test
    void enabledWithMemoryStoreInstallsNonDurableRecorder() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.tape.enabled=true",
                        "atmosphere.ai.tape.store=memory")
                .run(context -> {
                    var store = TapeSupport.installedStore().orElseThrow();
                    assertThat(store)
                            .as("the memory store backs the recorder")
                            .isInstanceOf(InMemoryTapeStore.class);
                    assertThat(store.durable())
                            .as("the in-memory store must not advertise crash-durability")
                            .isFalse();
                });
    }

    @Test
    void enabledWithUserTapeStoreBeanUsesIt() {
        var supplied = new InMemoryTapeStore();
        contextRunner
                .withBean(TapeStore.class, () -> supplied)
                .withPropertyValues("atmosphere.ai.tape.enabled=true")
                .run(context -> assertThat(TapeSupport.installedStore())
                        .as("a supplied TapeStore bean wins over the bundled default")
                        .contains(supplied));
    }

    /**
     * The writer thread persists asynchronously; poll until the run reaches
     * its COMPLETED terminal (bounded — the writer tick is sub-second).
     */
    private static org.atmosphere.ai.tape.TapeRun awaitCompletedRun(TapeStore store)
            throws InterruptedException {
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var completed = store.listRuns(TapeQuery.byStatus(TapeStatus.COMPLETED, 10));
            if (!completed.isEmpty()) {
                return completed.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("tape run did not reach COMPLETED within 10s: "
                + store.listRuns(TapeQuery.all(10)));
    }

    /** Minimal terminal-side {@link StreamingSession} the tape wraps. */
    private static final class PlainSession implements StreamingSession {
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
}
