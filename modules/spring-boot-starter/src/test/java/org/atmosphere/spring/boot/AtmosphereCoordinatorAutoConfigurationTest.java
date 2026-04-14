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

import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationJournalInspector;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.coordinator.processor.CoordinatorProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link AtmosphereCoordinatorAutoConfiguration} bridges a
 * Spring-managed {@link CoordinationJournal} bean into the
 * {@link CoordinatorProcessor}'s framework-property hook so the processor
 * actually consults it (instead of silently falling through to
 * {@link CoordinationJournal#NOOP} via {@code ServiceLoader}, which is the
 * Bug 4 regression this auto-config exists to close).
 */
class AtmosphereCoordinatorAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereCoordinatorAutoConfiguration.class));

    @Test
    void bridgeBeanAbsentWithoutJournal() {
        // No CoordinationJournal bean → @ConditionalOnBean(CoordinationJournal.class)
        // skips the bridge entirely. The framework property must remain unset
        // so the processor's ServiceLoader path is preserved unchanged.
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    AtmosphereCoordinatorAutoConfiguration.CoordinatorJournalBridge.class);
            assertThat(context).hasSingleBean(AtmosphereFramework.class);
            var framework = context.getBean(AtmosphereFramework.class);
            assertThat(framework.getAtmosphereConfig().properties()
                    .get(CoordinatorProcessor.COORDINATION_JOURNAL_PROPERTY))
                    .as("framework property must NOT be set when no journal bean exists")
                    .isNull();
        });
    }

    @Test
    void bridgeStashesJournalBeanOnFrameworkProperties() {
        contextRunner
                .withUserConfiguration(JournalBeanConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AtmosphereCoordinatorAutoConfiguration.CoordinatorJournalBridge.class);
                    assertThat(context).hasSingleBean(CoordinationJournal.class);

                    var journalBean = context.getBean(CoordinationJournal.class);
                    var framework = context.getBean(AtmosphereFramework.class);
                    var bridged = framework.getAtmosphereConfig().properties()
                            .get(CoordinatorProcessor.COORDINATION_JOURNAL_PROPERTY);

                    assertThat(bridged)
                            .as("bridge must stash the journal bean on framework "
                                    + "properties so CoordinatorProcessor.resolveJournal "
                                    + "can pick it up during annotation processing")
                            .isSameAs(journalBean);

                    var bridge = context.getBean(
                            AtmosphereCoordinatorAutoConfiguration.CoordinatorJournalBridge.class);
                    assertThat(bridge.journal()).isSameAs(journalBean);
                });
    }

    @Test
    void bridgeNeverStartsOrStopsTheJournalBean() {
        // The CheckpointingCoordinationJournal in the spring-boot-checkpoint-agent
        // sample owns its CheckpointStore lifecycle, and the underlying SQLite
        // store re-emits "SqliteCheckpointStore initialized" every time start()
        // is called. The bridge must therefore never invoke lifecycle methods
        // on a Spring-managed journal — Spring (or the bean itself) owns
        // start/stop.
        contextRunner
                .withUserConfiguration(JournalBeanConfig.class)
                .run(context -> {
                    var journal = (RecordingJournal) context.getBean(CoordinationJournal.class);
                    assertThat(journal.starts)
                            .as("the auto-config bridge must NEVER call start() on "
                                    + "a Spring-managed journal — the bean owns its "
                                    + "own lifecycle")
                            .isZero();
                    assertThat(journal.stops).isZero();
                });
    }

    @Configuration
    static class JournalBeanConfig {

        @Bean
        CoordinationJournal coordinationJournal() {
            return new RecordingJournal();
        }
    }

    /**
     * Counting test double — proves the auto-config never invokes lifecycle
     * methods on the bridged bean during context refresh.
     */
    static final class RecordingJournal implements CoordinationJournal {

        int starts;
        int stops;

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }

        @Override
        public void record(CoordinationEvent event) {
        }

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
}
