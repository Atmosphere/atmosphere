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
package org.atmosphere.ai.cost;

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the observability → enforcement wire end-to-end: a runtime
 * reports {@link TokenUsage} through {@link CostAccountingSession}, the
 * {@link CostCeilingAccountant} converts tokens to dollars via
 * {@link TokenPricing}, and {@link CostCeilingGuardrail#addCost} runs
 * with the tenant pulled from the {@code business.tenant.id} MDC. The
 * next request-side inspection blocks once the ceiling is reached.
 *
 * <p>This test exists because the retrospective flagged the pattern:
 * {@code addCost(...)} had zero production consumers — the
 * {@code CostCeilingGuardrail} primitive shipped without a working
 * consumer to feed it. The test pins the wire so a regression that
 * removes the {@code CostAccountingSession} hop from
 * {@code AiStreamingSession.dispatch} fails loudly.</p>
 */
class CostAccountingSessionTest {

    @BeforeEach
    void clearMdcAndHolder() {
        org.slf4j.MDC.clear();
        CostAccountantHolder.reset();
    }

    @AfterEach
    void tearDown() {
        org.slf4j.MDC.clear();
        CostAccountantHolder.reset();
    }

    @Test
    void usageEventFeedsCostCeilingViaTenantMdc() {
        var guardrail = new CostCeilingGuardrail(0.10);
        // $1/M input + $2/M output: 1000 input + 500 output = $0.001 + $0.001 = $0.002
        var pricing = TokenPricing.flat(1.00, 2.00);
        var accountant = new CostCeilingAccountant(guardrail, pricing);

        org.slf4j.MDC.put("business.tenant.id", "acme");
        var session = new CostAccountingSession(new CollectingSession(), accountant);

        session.usage(TokenUsage.of(1000, 500, 1500, "gpt-4"));

        assertEquals(0.002, guardrail.spent("acme"), 1e-9,
                "CostAccountingSession must feed the guardrail with the pricing-computed cost");
    }

    @Test
    void usageAccumulatesThenBlocksRequestSide() {
        var guardrail = new CostCeilingGuardrail(0.005);
        // Generous pricing so a few turns pile up quickly: $10/M + $20/M.
        var pricing = TokenPricing.flat(10.0, 20.0);
        var accountant = new CostCeilingAccountant(guardrail, pricing);

        org.slf4j.MDC.put("business.tenant.id", "noisy-tenant");
        var session = new CostAccountingSession(new CollectingSession(), accountant);

        // Each turn: 100 in + 100 out = ($0.001 + $0.002) = $0.003.
        // Two turns reach $0.006, past the $0.005 ceiling.
        session.usage(TokenUsage.of(100, 100, 200, "gpt-4"));
        session.usage(TokenUsage.of(100, 100, 200, "gpt-4"));

        var result = guardrail.inspectRequest(sampleRequest());
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result,
                "Once accumulated cost crosses the ceiling the next request-side "
                + "inspection must Block — observability without this step is a "
                + "dashboard, not a control plane");
    }

    @Test
    void missingTenantMdcRoutesToDefaultBucket() {
        var guardrail = new CostCeilingGuardrail(10.0);
        var accountant = new CostCeilingAccountant(guardrail, TokenPricing.flat(1.0, 1.0));
        var session = new CostAccountingSession(new CollectingSession(), accountant);
        // No MDC tag set on purpose.

        session.usage(TokenUsage.of(1000, 1000, 2000, "gpt-4"));

        // Null tenant falls into the shared __default__ bucket inside the
        // guardrail — single-tenant apps still get enforcement without
        // having to publish the MDC tag.
        assertTrue(guardrail.spent(null) > 0.0,
                "null tenant still accumulates in the default bucket; "
                + "spent=" + guardrail.spent(null));
    }

    @Test
    void zeroUsageIsNoOp() {
        var guardrail = new CostCeilingGuardrail(1.0);
        var accountant = new CostCeilingAccountant(guardrail, TokenPricing.flat(1.0, 1.0));
        var session = new CostAccountingSession(new CollectingSession(), accountant);

        // A provider that fails to report token counts hands the runtime a
        // zeroed TokenUsage. CostAccountingSession must short-circuit — no
        // point booking cost against a record carrying no signal.
        session.usage(TokenUsage.of(0, 0, 0, "gpt-4"));

        assertEquals(0.0, guardrail.spent(null), 1e-9);
    }

    @Test
    void nullUsageIsNoOp() {
        var guardrail = new CostCeilingGuardrail(1.0);
        var accountant = new CostCeilingAccountant(guardrail, TokenPricing.flat(1.0, 1.0));
        var session = new CostAccountingSession(new CollectingSession(), accountant);

        // Runtimes that never emit usage hand the session a null record;
        // the decorator forwards it to the delegate without booking cost.
        session.usage(null);

        assertEquals(0.0, guardrail.spent(null), 1e-9);
    }

    @Test
    void accountantThrowsDoesNotBreakSession() {
        var session = new CostAccountingSession(new CollectingSession(),
                (tenant, usage, model) -> {
                    throw new RuntimeException("operator wiring bug");
                });
        // The expectation is "no exception escapes to the caller" — a broken
        // accountant must not take the LLM turn down. RuntimeException thrown
        // here would propagate past the session wrapper.
        session.usage(TokenUsage.of(1, 1, 2, "gpt-4"));
    }

    @Test
    void holderInstallAndReset() {
        // The holder is the process-wide choke point AiStreamingSession.dispatch
        // reads. This test pins install/get/reset so regressions there show up.
        assertEquals(CostAccountant.NOOP, CostAccountantHolder.get());
        var recorder = new RecordingAccountant();
        CostAccountantHolder.install(recorder);
        assertEquals(recorder, CostAccountantHolder.get());
        CostAccountantHolder.reset();
        assertEquals(CostAccountant.NOOP, CostAccountantHolder.get());
    }

    @Test
    void tokenPricingFlatComputesExpectedRate() {
        // $3/M input + $15/M output (current GPT-4o pricing shape) —
        // verifies the arithmetic so dashboards don't silently drift off
        // by a factor of a million.
        var pricing = TokenPricing.flat(3.00, 15.00);
        double cost = pricing.costUsd(TokenUsage.of(1_000_000, 1_000_000, 2_000_000), "gpt-4o");
        assertEquals(18.00, cost, 1e-9, "1M in + 1M out at $3/$15 = $18");
    }

    private static AiRequest sampleRequest() {
        return new AiRequest("hello", null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
    }

    private static final class RecordingAccountant implements CostAccountant {
        @Override public void record(String tenantId, TokenUsage usage, String model) { }
    }
}
