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

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.cost.CostAccountant;
import org.atmosphere.ai.cost.CostAccountantHolder;
import org.atmosphere.ai.cost.TokenPricing;
import org.atmosphere.ai.cost.TokenPricingHolder;
import org.atmosphere.quarkus.runtime.AtmosphereCostAccountantProducer;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The no-phantom-enforcement half of the cost-ceiling parity contract
 * (Runtime Truth, Correctness Invariant #5): without
 * {@code quarkus.atmosphere.ai.guardrails.cost.*} config and without
 * {@code CostAccountant} / {@code CostCeilingGuardrail} / {@code TokenPricing}
 * CDI beans, the process-wide holders stay at their no-op defaults, no
 * guardrail is bridged into the framework chain, and
 * {@code CostAccountingSession} never wraps — mirroring the Spring test's
 * NOOP branch ({@code neitherGuardrailNorPricingLeavesHolderAtNoop}).
 */
public class CostAccountantNoConfigTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(CostAccountantNoConfigTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereCostAccountantProducer producer;

    @Test
    public void holderStaysAtNoopWithoutConfig() {
        assertSame(CostAccountant.NOOP, CostAccountantHolder.get(),
                "without budget config or beans the holder must stay at NOOP "
                        + "— no phantom enforcement");
        assertSame(TokenPricing.ZERO, TokenPricingHolder.get(),
                "without a pricing bean the pricing holder must stay at ZERO");
        assertNull(producer.installedAccountant(),
                "the producer must not have installed anything");
        assertNull(producer.effectiveGuardrail(),
                "no guardrail may be constructed without config or a user bean");
    }

    @Test
    public void noGuardrailBridgedIntoFrameworkChain() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework,
                "the Atmosphere servlet must have initialized during boot (loadOnStartup=1)");
        assertNull(framework.getAtmosphereConfig().properties()
                        .get(AiGuardrail.GUARDRAILS_PROPERTY),
                "no guardrail list may be bridged when nothing is configured");
    }
}
