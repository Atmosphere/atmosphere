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

import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.processor.CoordinatorProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that bridges a Spring-managed {@link CoordinationJournal}
 * bean into the {@link CoordinatorProcessor}'s discovery path.
 *
 * <p>The processor's built-in resolution uses {@link java.util.ServiceLoader},
 * which only finds classes registered via {@code META-INF/services/} — Spring
 * beans are invisible to it. Without this bridge, a Spring Boot 3 consumer that
 * wires a {@code @Bean CoordinationJournal coordinationJournal(...)} would
 * silently fall through to {@link CoordinationJournal#NOOP}, every
 * {@code AgentCompleted}/{@code AgentFailed} event would be discarded inside
 * {@code DefaultAgentFleet}, and the coordinator-driven path would write zero
 * snapshots to the configured {@code CheckpointStore}.</p>
 *
 * <p>The bridge stashes the journal on
 * {@code framework.getAtmosphereConfig().properties()} under
 * {@link CoordinatorProcessor#COORDINATION_JOURNAL_PROPERTY} <em>before</em>
 * the embedded servlet container calls {@code AtmosphereServlet.init()},
 * so the property is in place when the annotation processor runs and
 * resolves the journal for each {@code @Coordinator} bean.</p>
 *
 * <p>Lifecycle ownership: the journal is Spring-owned. The processor will
 * <strong>not</strong> call {@link CoordinationJournal#start()} or
 * {@link CoordinationJournal#stop()} on a bridged journal — the bean's
 * {@code @Bean(destroyMethod = "stop")} (or equivalent) is responsible.</p>
 *
 * <p>The auto-config is silently skipped when {@code atmosphere-coordinator}
 * is absent from the classpath (it is an optional dependency of
 * {@code atmosphere-spring-boot3-starter}), and when no
 * {@link CoordinationJournal} bean is present in the application context.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(CoordinatorProcessor.class)
@ConditionalOnBean(AtmosphereFramework.class)
public class AtmosphereCoordinatorAutoConfiguration {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereCoordinatorAutoConfiguration.class);

    /**
     * Bridges the application's {@link CoordinationJournal} bean (if any) into
     * the framework's properties so {@link CoordinatorProcessor} can pick it
     * up during annotation processing.
     *
     * <p>The returned {@link CoordinatorJournalBridge} is a marker bean that
     * exists so Spring's bean graph records the dependency on both
     * {@link AtmosphereFramework} and the journal — that ordering guarantees
     * the property write happens before the embedded servlet container
     * starts and triggers framework initialization.</p>
     */
    @Bean
    @ConditionalOnBean(CoordinationJournal.class)
    @ConditionalOnMissingBean(CoordinatorJournalBridge.class)
    public CoordinatorJournalBridge atmosphereCoordinatorJournalBridge(
            AtmosphereFramework framework, CoordinationJournal journal) {
        framework.getAtmosphereConfig().properties()
                .put(CoordinatorProcessor.COORDINATION_JOURNAL_PROPERTY, journal);
        logger.info("Bridged Spring CoordinationJournal bean {} into "
                + "CoordinatorProcessor (lifecycle: Spring-owned)",
                journal.getClass().getName());
        return new CoordinatorJournalBridge(journal);
    }

    /**
     * Marker bean recording that a Spring-managed journal has been bridged
     * into the processor. The bean carries the journal reference so tests and
     * diagnostics can verify the bridge was wired without poking at framework
     * properties directly.
     *
     * @param journal the Spring-managed journal that was bridged
     */
    public record CoordinatorJournalBridge(CoordinationJournal journal) {
    }
}
