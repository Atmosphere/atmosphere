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
import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.DurableRunSpineHolder;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 3 parity for the durable-run enablement wiring, pinning the full
 * on-disk chain <em>HTTP-run path → DurableRunSpine scope → EffectJournal memo →
 * SqliteEffectJournal persists on disk</em>: off by default, opt-in installs an
 * enabled spine, and with the bundled SQLite journal a tool call driven through
 * the shared seam lands as a committed effect in a real {@code .db} file.
 * Mirrors the SB4 starter's {@code DurableRunSpineAutoConfigurationTest}.
 */
class DurableRunSpineAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    @BeforeEach
    @AfterEach
    void reset() {
        DurableRunSpineHolder.reset();
        DurableRunScopeHolder.clear();
    }

    @Test
    void offByDefaultInstallsNoSpine() {
        contextRunner.run(context -> {
            assertThat(context)
                    .doesNotHaveBean(AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
            assertThat(DurableRunSpineHolder.get().enabled())
                    .as("no opt-in means the default disabled spine")
                    .isFalse();
        });
    }

    @Test
    void enabledWithSqliteJournalPersistsAToolEffectOnDisk(@TempDir Path tmp) {
        var db = tmp.resolve("runs.db");
        contextRunner
                .withPropertyValues(
                        "atmosphere.durable-runs.enabled=true",
                        "atmosphere.durable-runs.journal=sqlite",
                        "atmosphere.durable-runs.path=" + db)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
                    var spine = DurableRunSpineHolder.get();
                    assertThat(spine.enabled()).isTrue();
                    assertThat(spine.journal().durable())
                            .as("the bundled SQLite journal is crash-durable")
                            .isTrue();

                    // Drive a tool call through the same cross-runtime seam a real
                    // run reaches, with the autoconfig-installed spine driving it.
                    var runId = "run-it";
                    var ctx = spine.beginDrive(runId, "alice", "/chat").orElseThrow();
                    var session = new RunSession(runId);
                    var executor = new CountingExecutor();
                    var echo = new ToolDefinition("echo", "echo", List.of(), "string", executor, null, 0);
                    var result = ToolExecutionHelper.executeWithApproval(
                            "echo", echo, Map.of("id", "v"), session, null, null, Map.of());

                    assertThat(result).isEqualTo("ok");
                    var key = EffectKeys.toolCall(runId, "echo", Map.of("id", "v"), 0);
                    assertThat(spine.journal().lookupCommitted(runId, key))
                            .as("the tool effect is committed to the journal")
                            .isPresent();
                    assertThat(Files.exists(db))
                            .as("the effect was persisted to a real on-disk SQLite file")
                            .isTrue();
                    assertThat(db.toFile().length())
                            .as("the database file holds data")
                            .isGreaterThan(0L);

                    spine.completeDrive(ctx, true);
                });
    }

    @Test
    void enabledWithMemoryJournalInstallsNonDurableSpine() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.durable-runs.enabled=true",
                        "atmosphere.durable-runs.journal=memory")
                .run(context -> {
                    var spine = DurableRunSpineHolder.get();
                    assertThat(spine.enabled()).isTrue();
                    assertThat(spine.journal())
                            .as("the memory journal backs the spine")
                            .isInstanceOf(InMemoryEffectJournal.class);
                    assertThat(spine.journal().durable())
                            .as("the in-memory journal must not advertise crash-durability")
                            .isFalse();
                });
    }

    @Test
    void enabledWithUserEffectJournalBeanUsesIt() {
        var supplied = new InMemoryEffectJournal();
        contextRunner
                .withBean(org.atmosphere.ai.resume.EffectJournal.class, () -> supplied)
                .withPropertyValues("atmosphere.durable-runs.enabled=true")
                .run(context -> assertThat(DurableRunSpineHolder.get().journal())
                        .as("a supplied EffectJournal bean wins over the bundled default")
                        .isSameAs(supplied));
    }

    private static final class CountingExecutor implements ToolExecutor {
        @Override
        public Object execute(Map<String, Object> arguments) {
            return "ok";
        }
    }

    private record RunSession(String id) implements StreamingSession {
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
}
