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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the governance decision log is installed out-of-box by the admin
 * auto-configuration so the queryable trail (console governance view + any
 * Kafka/Postgres {@code AuditSink}) records admit + deny decisions instead of
 * silently dropping them against the NOOP default. Gated by
 * {@code atmosphere.ai.governance.decision-log.capacity} (default 500, 0
 * disables); never clobbers an operator's own {@code install(...)}.
 */
class AtmosphereGovernanceDecisionLogAutoConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAdminAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetLog() {
        // The decision log is a process-wide singleton; reset around each run so
        // a prior install cannot leak into (or out of) the assertion.
        GovernanceDecisionLog.reset();
    }

    @Test
    void installsDecisionLogOutOfBox() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(GovernanceDecisionLog.installed().capacity())
                    .as("admin auto-config must install a non-NOOP decision log by default")
                    .isEqualTo(GovernanceDecisionLog.DEFAULT_CAPACITY);
        });
    }

    @Test
    void capacityZeroDisablesInstall() {
        contextRunner
                .withPropertyValues("atmosphere.ai.governance.decision-log.capacity=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(GovernanceDecisionLog.installed().capacity())
                            .as("capacity=0 must leave the NOOP log (feature disabled)")
                            .isZero();
                });
    }

    @Test
    void respectsCustomCapacity() {
        contextRunner
                .withPropertyValues("atmosphere.ai.governance.decision-log.capacity=128")
                .run(context -> assertThat(GovernanceDecisionLog.installed().capacity())
                        .isEqualTo(128));
    }

    @Test
    void doesNotClobberOperatorInstalledLog() {
        // An operator who installed their own log (e.g. one already carrying an
        // audit sink) must not be overwritten by the default install.
        GovernanceDecisionLog.install(4096);
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(GovernanceDecisionLog.installed().capacity())
                    .as("a pre-installed operator log must survive auto-config")
                    .isEqualTo(4096);
        });
    }
}
