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

import org.atmosphere.ai.cost.CostAccountant;
import org.atmosphere.ai.guardrails.PiiRedactionGuardrail;
import org.atmosphere.ai.resume.InMemoryRunJournal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity for the non-routing AI beans on the <strong>Spring Boot 3</strong>
 * starter: opt-in guardrails, the cost-accountant installer, and the
 * crash-durable run-registry installer. Mirrors the SB4 starter's AI
 * auto-configuration so an SB3 deployment is no longer missing these AI beans.
 */
class AtmosphereAiBeansAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    @Test
    void piiGuardrailOffByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(PiiRedactionGuardrail.class));
    }

    @Test
    void piiGuardrailWiredWhenEnabled() {
        contextRunner
                .withPropertyValues("atmosphere.ai.guardrails.pii.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(PiiRedactionGuardrail.class);
                    // The stream-path complement is wired under the same flag.
                    assertThat(context).hasSingleBean(org.atmosphere.ai.filter.PiiRedactionFilter.class);
                });
    }

    @Test
    void costAccountantInstallerIsNoopByDefault() {
        contextRunner.run(context -> {
            var installer = context.getBean(
                    AtmosphereAiAutoConfiguration.CostAccountantInstaller.class);
            assertThat(installer.source()).contains("NOOP");
            assertThat(installer.accountant()).isSameAs(CostAccountant.NOOP);
        });
    }

    @Test
    void costAccountantInstallerPrefersUserBean() {
        contextRunner
                .withBean("userAccountant", CostAccountant.class,
                        AtmosphereAiBeansAutoConfigurationTest::userAccountant)
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.CostAccountantInstaller.class);
                    assertThat(installer.source()).isEqualTo("user-bean");
                    assertThat(installer.accountant()).isNotSameAs(CostAccountant.NOOP);
                });
    }

    @Test
    void runRegistryInstallerOffByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(
                        AtmosphereAiAutoConfiguration.RunRegistryInstaller.class));
    }

    @Test
    void runRegistryInstallerUsesInMemoryJournalWhenEnabled() {
        contextRunner
                .withPropertyValues("atmosphere.ai.resume.durable.enabled=true")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RunRegistryInstaller.class);
                    assertThat(installer.journal()).isInstanceOf(InMemoryRunJournal.class);
                });
    }

    /** A distinct (non-NOOP) CostAccountant so the user-bean path is exercised. */
    private static CostAccountant userAccountant() {
        return (tenantId, usage, model) -> {
            // test double — records nothing
        };
    }
}
