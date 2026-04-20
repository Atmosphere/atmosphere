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

import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.cost.CostAccountant;
import org.atmosphere.ai.cost.CostAccountantHolder;
import org.atmosphere.ai.cost.CostCeilingAccountant;
import org.atmosphere.ai.cost.TokenPricing;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the three branches of
 * {@link AtmosphereAiAutoConfiguration.CostAccountantInstaller}: user-
 * supplied accountant wins, guardrail + pricing compose a
 * {@link CostCeilingAccountant}, nothing wired leaves the holder at NOOP.
 * The installer is the production consumer that moves
 * {@link CostCeilingGuardrail#addCost} from "API + reference impl,
 * integration pending" to a working observability → enforcement loop.
 */
class AtmosphereCostAccountantAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetHolder() {
        // The installer writes to a process-wide holder; reset before
        // and after each run so cross-test pollution can't pass the
        // assertion by accident.
        CostAccountantHolder.reset();
    }

    @Test
    void neitherGuardrailNorPricingLeavesHolderAtNoop() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtmosphereAiAutoConfiguration.CostAccountantInstaller.class);
            var installer = context.getBean(AtmosphereAiAutoConfiguration.CostAccountantInstaller.class);
            assertThat(installer.accountant()).isEqualTo(CostAccountant.NOOP);
            assertThat(installer.source()).contains("NOOP");
            assertThat(CostAccountantHolder.get()).isEqualTo(CostAccountant.NOOP);
        });
    }

    @Test
    void guardrailPlusPricingWiresCostCeilingAccountant() {
        contextRunner
                .withUserConfiguration(GuardrailAndPricingConfig.class)
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.CostAccountantInstaller.class);
                    assertThat(installer.accountant()).isInstanceOf(CostCeilingAccountant.class);
                    assertThat(installer.source()).contains("CostCeilingAccountant");

                    // Drive the wire end-to-end: record a TokenUsage event via
                    // the holder-installed accountant and assert the guardrail
                    // bucket moved. This is the proof that the primitive has a
                    // production consumer, not just a reference implementation.
                    CostAccountantHolder.get().record("acme",
                            TokenUsage.of(1000, 500, 1500, "gpt-4"),
                            "gpt-4");
                    var guardrail = context.getBean(CostCeilingGuardrail.class);
                    assertThat(guardrail.spent("acme")).isGreaterThan(0.0);
                });
    }

    @Test
    void userSuppliedAccountantWinsOverBuiltIn() {
        contextRunner
                .withUserConfiguration(UserAccountantConfig.class)
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.CostAccountantInstaller.class);
                    assertThat(installer.accountant())
                            .as("User-supplied CostAccountant bean must take precedence over "
                                    + "the built-in CostCeilingAccountant — operators with custom "
                                    + "attribution (Micrometer, ledger) opt out of the built-in path")
                            .isSameAs(context.getBean(CostAccountant.class));
                    assertThat(installer.source()).contains("user-bean");
                });
    }

    @Configuration
    static class GuardrailAndPricingConfig {
        @Bean
        CostCeilingGuardrail guardrail() {
            return new CostCeilingGuardrail(100.0);
        }

        @Bean
        TokenPricing pricing() {
            return TokenPricing.flat(1.0, 2.0);
        }
    }

    @Configuration
    static class UserAccountantConfig {
        @Bean
        CostAccountant userAccountant() {
            return (tenant, usage, model) -> { };
        }
    }
}
