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

import org.atmosphere.ai.resume.InMemoryRunJournal;
import org.atmosphere.ai.resume.RunEvent;
import org.atmosphere.ai.resume.RunJournal;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the opt-in crash-durable run-resume wiring (P2.21): off by default;
 * when enabled, installs a journal-backed {@code RunRegistry} into the
 * holder — an in-memory journal (not crash-durable) by default, or a
 * supplied durable {@link RunJournal} bean when present.
 */
class RunRegistryDurableAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetHolder() {
        RunRegistryHolder.reset();
    }

    @Test
    void offByDefaultLeavesHolderWithNoopJournal() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AtmosphereAiAutoConfiguration.RunRegistryInstaller.class);
            assertThat(RunRegistryHolder.get().journal())
                    .as("no installer means the default in-memory (NOOP-journal) registry")
                    .isSameAs(RunJournal.NOOP);
        });
    }

    @Test
    void enabledWithoutJournalBeanInstallsInMemoryNonDurable() {
        contextRunner
                .withPropertyValues("atmosphere.ai.resume.durable.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtmosphereAiAutoConfiguration.RunRegistryInstaller.class);
                    var journal = RunRegistryHolder.get().journal();
                    assertThat(journal).isInstanceOf(InMemoryRunJournal.class);
                    assertThat(journal.durable())
                            .as("default in-memory journal must not advertise crash-durability")
                            .isFalse();
                });
    }

    @Test
    void enabledWithDurableJournalBeanInstallsIt() {
        var durable = new RecordingDurableJournal();
        contextRunner
                .withBean(RunJournal.class, () -> durable)
                .withPropertyValues("atmosphere.ai.resume.durable.enabled=true")
                .run(context -> {
                    var journal = RunRegistryHolder.get().journal();
                    assertThat(journal)
                            .as("a supplied durable journal bean is installed onto the live registry")
                            .isSameAs(durable);
                    assertThat(journal.durable()).isTrue();
                });
    }

    /** Minimal durable-reporting journal so the test can assert the durable path. */
    private static final class RecordingDurableJournal implements RunJournal {
        @Override
        public void recordRun(RunRecord run) {
            // no-op
        }

        @Override
        public void appendEvent(String runId, RunEvent event) {
            // no-op
        }

        @Override
        public void removeRun(String runId) {
            // no-op
        }

        @Override
        public List<RunRecord> loadAll() {
            return List.of();
        }

        @Override
        public List<RunEvent> loadEvents(String runId) {
            return List.of();
        }

        @Override
        public boolean durable() {
            return true;
        }
    }
}
