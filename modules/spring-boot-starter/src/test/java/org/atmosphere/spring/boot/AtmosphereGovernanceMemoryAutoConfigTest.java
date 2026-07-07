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

import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.memory.GovernanceMemoryConfig;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.atmosphere.ai.memory.LongTermMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the opt-in durable governance-feedback wiring: off by default (ephemeral loop
 * only); when {@code atmosphere.ai.governance.memory.enabled=true} the provenance store is
 * published and the {@code governance-memory} audit sink is registered so deny/prefer decisions
 * persist — using the application's {@link LongTermMemory} bean when present, else the resolved
 * fallback store (logged as non-persistent, Invariant #5). Shares one installer with the
 * framework-agnostic path so Quarkus / bare-JVM behave identically.
 */
class AtmosphereGovernanceMemoryAutoConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void reset() {
        GovernanceDecisionLog.reset();
        GovernanceMemoryConfig.resetStore();
    }

    @Test
    void durableOffByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(
                    AtmosphereAiAutoConfiguration.GovernanceMemoryWiring.class);
            assertThat(GovernanceMemoryConfig.installedStore())
                    .as("no durable store published by default")
                    .isNull();
        });
    }

    @Test
    void enabledWithStorePublishesStoreAndRegistersSink() {
        contextRunner
                .withPropertyValues("atmosphere.ai.governance.memory.enabled=true")
                .withBean(LongTermMemory.class, InMemoryLongTermMemory::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            AtmosphereAiAutoConfiguration.GovernanceMemoryWiring.class).active())
                            .isTrue();
                    assertThat(GovernanceMemoryConfig.installedStore())
                            .as("provenance store published for the interceptor to recall from")
                            .isNotNull();
                    assertThat(GovernanceDecisionLog.installed().sinks())
                            .anyMatch(s -> "governance-memory".equals(s.name()));
                });
    }

    @Test
    void enabledWithoutStoreUsesResolvedFallback() {
        // No LongTermMemory bean: the wiring still activates using the resolved fallback store
        // (in-memory), logged as non-persistent — consistent with the framework-agnostic path.
        contextRunner
                .withPropertyValues("atmosphere.ai.governance.memory.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            AtmosphereAiAutoConfiguration.GovernanceMemoryWiring.class).active())
                            .as("enabled -> activates with the resolved fallback store")
                            .isTrue();
                    assertThat(GovernanceMemoryConfig.installedStore()).isNotNull();
                });
    }
}
