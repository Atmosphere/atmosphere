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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.cost.CostAccountant;
import org.atmosphere.ai.cost.CostAccountantHolder;
import org.atmosphere.ai.cost.CostCeilingAccountant;
import org.atmosphere.ai.cost.TokenPricing;
import org.atmosphere.quarkus.runtime.AtmosphereCostAccountantProducer;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the cost-accountant {@code @BuildStep} (Spring Boot parity for
 * {@code AtmosphereAiAutoConfiguration.CostAccountantInstaller}). Boots
 * Quarkus with {@code quarkus.atmosphere.ai.guardrails.cost.enabled=true},
 * a USD budget, and a {@code TokenPricing} CDI bean, then proves the whole
 * observability → enforcement loop the Spring side ships: the process-wide
 * {@link CostAccountantHolder} carries a {@link CostCeilingAccountant}
 * (non-NOOP), a recorded {@code TokenUsage} accrues dollar spend, the
 * accrued spend trips the guardrail's request-side Block, and the same
 * guardrail instance was bridged into the framework-scoped
 * {@link AiGuardrail#GUARDRAILS_PROPERTY} so {@code @AiEndpoint} chains
 * actually enforce it. The test would FAIL without
 * {@code AtmosphereProcessor.registerCostAccountantProducer} (holder stays
 * NOOP) or without the {@code QuarkusAtmosphereServlet} pre-init bridge
 * (no guardrail in the chain — budget never blocks).
 */
public class CostCeilingAccountantBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    CostCeilingAccountantBuildStepTest.class, PricingConfig.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.atmosphere.ai.guardrails.cost.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.ai.guardrails.cost.budget-usd", "0.005")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereCostAccountantProducer producer;

    /** Supplies the rate sheet — mirrors the Spring test's pricing bean. */
    @ApplicationScoped
    static class PricingConfig {
        @Produces
        @Singleton
        TokenPricing pricing() {
            return TokenPricing.flat(1.0, 2.0);
        }
    }

    @Test
    public void holderCarriesCostCeilingAccountant() {
        assertNotSame(CostAccountant.NOOP, CostAccountantHolder.get(),
                "with budget config + pricing bean the holder must not stay at NOOP");
        assertInstanceOf(CostCeilingAccountant.class, CostAccountantHolder.get(),
                "the built-in CostCeilingAccountant must be installed");
        assertSame(producer.installedAccountant(), CostAccountantHolder.get(),
                "the holder must carry exactly what the producer installed");
        assertTrue(producer.source().contains("CostCeilingAccountant"),
                "install path must be guardrail+pricing, got: " + producer.source());
    }

    @Test
    public void accrualTripsTheGuardrailBlock() {
        var guardrail = producer.effectiveGuardrail();
        assertNotNull(guardrail, "config-built guardrail must exist");

        MDC.put("business.tenant.id", "acme-trip");
        try {
            assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                    guardrail.inspectRequest(new AiRequest("hello")),
                    "under budget the request must pass");

            // 2000 in @ $1/M + 1000 out @ $2/M = $0.004 per turn; two turns
            // ($0.008) cross the $0.005 ceiling.
            var usage = TokenUsage.of(2_000, 1_000, 3_000, "test-model");
            CostAccountantHolder.get().record("acme-trip", usage, "test-model");
            CostAccountantHolder.get().record("acme-trip", usage, "test-model");
            assertTrue(guardrail.spent("acme-trip") >= 0.008,
                    "recorded usage must accrue spend, got " + guardrail.spent("acme-trip"));

            var block = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                    guardrail.inspectRequest(new AiRequest("hello")),
                    "over budget the request must Block");
            assertTrue(block.reason().contains("acme-trip"),
                    "the block reason names the tenant: " + block.reason());
        } finally {
            MDC.remove("business.tenant.id");
            guardrail.resetTenant("acme-trip");
        }
    }

    @Test
    public void guardrailBridgedIntoFrameworkChain() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework,
                "the Atmosphere servlet must have initialized during boot (loadOnStartup=1)");
        var bridged = framework.getAtmosphereConfig().properties()
                .get(AiGuardrail.GUARDRAILS_PROPERTY);
        assertInstanceOf(List.class, bridged,
                "the pre-init bridge must publish a guardrail list into framework properties");
        assertTrue(((List<?>) bridged).contains(producer.effectiveGuardrail()),
                "the bridged list must contain the exact guardrail instance the "
                        + "accountant feeds — otherwise blocks and accrual act on "
                        + "different buckets");
    }
}
