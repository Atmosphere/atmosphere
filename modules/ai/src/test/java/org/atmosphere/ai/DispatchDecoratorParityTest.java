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
package org.atmosphere.ai;

import org.atmosphere.ai.cost.CostAccountantHolder;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the unified dispatch decorator chain — the fix for the Tier-1
 * mode-parity P1 where {@code AiStreamingSession} (@AiEndpoint) and
 * {@code AiPipeline} (channel/@Coordinator) hand-assembled divergent stacks:
 * budget existed only on the pipeline; cost accounting, lineage, and the dev
 * inspector only on the streaming path. Both paths now route the shared
 * layers through {@link DispatchDecorators#compose}; this test pins the
 * canonical order and the gating so a future one-sided addition or reorder
 * breaks the build (the same pinning discipline as
 * {@code AbstractAgentRuntimeContractTest.expectedCapabilities()}).
 */
class DispatchDecoratorParityTest {

    @AfterEach
    void resetHolders() {
        CostAccountantHolder.reset();
        LineageRecorderHolder.reset();
    }

    private static DispatchDecorators.Spec spec(AiBudget budget, Class<?> responseType,
                                                AiConfidenceElicitation confidence) {
        return new DispatchDecorators.Spec(
                null, "client-1", "hello",
                "user-1", "agent-1", "conv-1",
                AiMetrics.NOOP, "test-model", "test-runtime", "test-model",
                budget, List.of(), responseType, confidence);
    }

    @Test
    void canonicalOrderIsPinnedWithAllSharedLayersActive() {
        // Install real (non-NOOP) holder-backed layers so cost + lineage wrap.
        CostAccountantHolder.install(new org.atmosphere.ai.cost.CostCeilingAccountant(
                new CostCeilingGuardrail(1000.0),
                org.atmosphere.ai.cost.TokenPricing.flat(3.0, 15.0)));
        LineageRecorderHolder.install(entry -> { });
        var budget = AiBudget.ofTokens(1_000_000);
        var base = new CollectingSession();

        var composed = DispatchDecorators.compose(base,
                new DispatchDecorators.Spec(
                        null, "client-1", "hello",
                        "user-1", "agent-1", "conv-1",
                        new MicrometerAiMetrics(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                                "test"),
                        "test-model", "test-runtime", "test-model",
                        budget, List.of(new org.atmosphere.ai.guardrails.PiiRedactionGuardrail()),
                        null, AiConfidenceElicitation.defaults()));

        assertEquals(List.of("lineage", "metrics", "cost", "budget",
                        "guardrails", "confidence"),
                composed.layers(),
                "the canonical shared-layer order is pinned — a reorder or a "
                        + "one-sided addition must fail this test");
        assertNotNull(composed.budgetSession(),
                "an enforced budget must surface its session for setOnTrip binding");
        assertNotNull(composed.confidenceCueText());
    }

    @Test
    void confidenceIsSkippedWhenStructuredOutputDeclared() {
        record Answer(String text) { }
        var composed = DispatchDecorators.compose(new CollectingSession(),
                spec(null, Answer.class, AiConfidenceElicitation.defaults()));

        assertTrue(composed.layers().contains("structured-output"));
        assertTrue(!composed.layers().contains("confidence"),
                "the confidence cue would break the single-JSON-object parse");
        assertNull(composed.confidenceCueText());
        assertNotNull(composed.structuredSchemaText());
    }

    @Test
    void bareSpecComposesNoLayers() {
        var base = new CollectingSession();
        var composed = DispatchDecorators.compose(base, spec(null, null, null));

        assertEquals(List.of(), composed.layers(),
                "no features -> the chain stays flat (zero-cost default)");
        assertEquals(base, composed.target());
        assertNull(composed.budgetSession());
    }

    @Test
    void bothDispatchPathsRouteThroughTheSharedComposer() throws Exception {
        // Structural pin: the two entry modes must CALL the composer — the
        // whole point of the fix is that the shared layers cannot drift apart
        // again. A hand-rolled re-assembly in either file fails here.
        var root = Path.of("src/main/java/org/atmosphere/ai");
        var streaming = Files.readString(root.resolve("AiStreamingSession.java"));
        var pipeline = Files.readString(root.resolve("AiPipeline.java"));
        var call = Pattern.compile("DispatchDecorators\\.compose\\(");

        assertTrue(call.matcher(streaming).find(),
                "AiStreamingSession must route its shared decorators through "
                        + "DispatchDecorators.compose");
        assertTrue(call.matcher(pipeline).find(),
                "AiPipeline must route its shared decorators through "
                        + "DispatchDecorators.compose");
    }
}
