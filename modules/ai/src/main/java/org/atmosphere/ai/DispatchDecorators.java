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

import org.atmosphere.ai.cost.CostAccountant;
import org.atmosphere.ai.cost.CostAccountantHolder;
import org.atmosphere.ai.cost.CostAccountingSession;
import org.atmosphere.ai.devinspector.DevInspectorRecorder;
import org.atmosphere.ai.devinspector.DevInspectorRecorderHolder;
import org.atmosphere.ai.lineage.LineageCapturingSession;
import org.atmosphere.ai.lineage.LineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE composer for the resource-agnostic capture/enforcement decorator
 * layers shared by both dispatch entry modes — the {@code @AiEndpoint}
 * websocket path ({@link AiStreamingSession}) and the channel /
 * {@code @Coordinator} / A2A / AG-UI path ({@link AiPipeline}).
 *
 * <p>Before this seam existed, each path hand-assembled its own chain and they
 * drifted (the Tier-1 mode-parity P1): budget enforcement existed only on the
 * pipeline, while cost accounting, lineage audit, and the dev inspector existed
 * only on the streaming path — so an {@code @AiEndpoint} could not enforce a
 * budget and coordinator/channel traffic never accrued cost or audit rows
 * (Correctness Invariant #7). Routing both paths through this composer makes a
 * future one-sided addition structurally impossible for the shared layers, and
 * {@code DispatchDecoratorParityTest} pins the canonical order.</p>
 *
 * <h2>Canonical order (innermost → outermost)</h2>
 * <ol>
 *   <li><b>Memory</b> — innermost so it persists exactly what the client saw.</li>
 *   <li><b>Lineage</b> — outside memory so the audit row's terminal
 *       classification reflects what the memory capture sees.</li>
 *   <li><b>Metrics</b> — outside lineage; must see raw counts on a
 *       budget-breaching call (budget sits outside it).</li>
 *   <li><b>Cost accounting</b> — outside metrics: usage feeds the installed
 *       {@code CostAccountant} → {@code CostCeilingGuardrail.addCost}.</li>
 *   <li><b>Budget</b> — outside cost so the breaching turn still accrues
 *       spend and records its metrics before the trip cancels the run.</li>
 *   <li><b>Dev inspector</b> — dev-only opt-in capture.</li>
 *   <li><b>Guardrails</b> — post-response inspection over everything the
 *       counting layers already observed.</li>
 *   <li><b>Structured output</b> — typed parsing; guardrails see parsed
 *       output through it.</li>
 *   <li><b>Confidence</b> — outermost of the shared layers; skipped when a
 *       structured response type is declared (the cue block would break the
 *       single-JSON-object parse).</li>
 * </ol>
 *
 * <p>Path-specific compensations stay OUT of this composer by design:
 * {@code GovernancePolicyInjectingSession} and {@code ToolInjectablesSession}
 * exist on the pipeline only because it has no {@code AtmosphereResource} —
 * on the streaming path the same injectables/policies ride the resource, and
 * composing them on both sides would double-inject. The pipeline's response
 * cache gate likewise stays in {@link AiPipeline} (the endpoint path has no
 * cache source today — see the register's semantic-cache item).</p>
 */
final class DispatchDecorators {

    private DispatchDecorators() {
    }

    /**
     * Feature inputs for one dispatch. {@code null} (or NOOP-holder) members
     * skip their layer, preserving each path's existing gating.
     *
     * @param memory            conversation memory, or {@code null}
     * @param memoryClientId    the client/resource id the memory capture keys on
     * @param message           the user message (memory + dev inspector)
     * @param lineageUserId     lineage principal, or {@code null} (pipeline)
     * @param lineageAgentId    lineage agent id, or {@code null} (pipeline)
     * @param lineageConversationId lineage conversation key (clientId on the pipeline)
     * @param metrics           the metrics sink ({@link AiMetrics#NOOP} skips)
     * @param metricsModel      the model tag for the metrics layer
     * @param runtimeName       the resolved runtime name for the metrics layer
     * @param inspectorModel    the model tag for the dev-inspector layer
     * @param budget            the enforced budget, or {@code null}
     * @param guardrails        post-response checks; empty skips
     * @param responseType      declared structured type, or {@code null}/{@code Void}
     * @param confidence        confidence elicitation, or {@code null}; ignored
     *                          when {@code responseType} is declared
     * @param originatingRequest the request that produces the stream, handed to
     *                          the guardrail layer so identity-scoped output
     *                          checks keep their subject; or {@code null}
     */
    record Spec(
            AiConversationMemory memory,
            String memoryClientId,
            String message,
            String lineageUserId,
            String lineageAgentId,
            String lineageConversationId,
            AiMetrics metrics,
            String metricsModel,
            String runtimeName,
            String inspectorModel,
            AiBudget budget,
            List<AiGuardrail> guardrails,
            Class<?> responseType,
            AiConfidenceElicitation confidence,
            AiRequest originatingRequest) {
    }

    /**
     * The composed chain plus the seams the caller must wire post-dispatch.
     *
     * @param target             the outermost session to hand to the runtime
     * @param budgetSession      non-null when a budget layer was applied — the
     *                           caller MUST bind {@code setOnTrip(handle::cancel)}
     *                           after {@code executeWithHandle} so a trip cancels
     *                           the in-flight call (Terminal Path, Invariant #2)
     * @param structuredSchemaText schema instructions to append to the system
     *                           prompt (cache-prefix contract), or {@code null}
     * @param confidenceCueText  confidence cue to append after the schema text,
     *                           or {@code null}
     * @param layers             the applied layer names, innermost first — built
     *                           from the actual wraps (runtime truth) and pinned
     *                           by the parity test
     */
    record Composed(
            StreamingSession target,
            BudgetCapturingSession budgetSession,
            String structuredSchemaText,
            String confidenceCueText,
            List<String> layers) {
    }

    /** Compose the shared decorator layers over {@code base} per the spec. */
    static Composed compose(StreamingSession base, Spec spec) {
        var target = base;
        var layers = new ArrayList<String>(9);

        if (spec.memory() != null) {
            target = new MemoryCapturingSession(target, spec.memory(),
                    spec.memoryClientId(), spec.message());
            layers.add("memory");
        }

        var lineageRecorder = LineageRecorderHolder.get();
        if (lineageRecorder != LineageRecorder.NOOP) {
            target = new LineageCapturingSession(target, lineageRecorder,
                    spec.lineageUserId(), spec.lineageAgentId(),
                    spec.lineageConversationId(), spec.message());
            layers.add("lineage");
        }

        if (spec.metrics() != AiMetrics.NOOP) {
            target = new MetricsCapturingSession(target, spec.metrics(),
                    spec.metricsModel(), spec.runtimeName());
            layers.add("metrics");
        }

        var accountant = CostAccountantHolder.get();
        if (accountant != CostAccountant.NOOP) {
            target = new CostAccountingSession(target, accountant);
            layers.add("cost");
        }

        BudgetCapturingSession budgetSession = null;
        if (spec.budget() != null && spec.budget().enforced()) {
            budgetSession = new BudgetCapturingSession(target, spec.budget());
            target = budgetSession;
            layers.add("budget");
        }

        var devInspector = DevInspectorRecorderHolder.get();
        if (devInspector != DevInspectorRecorder.NOOP) {
            target = new DevInspectorCapturingSession(target, devInspector,
                    spec.inspectorModel(), spec.message());
            layers.add("dev-inspector");
        }

        if (spec.guardrails() != null && !spec.guardrails().isEmpty()) {
            target = new GuardrailCapturingSession(target, spec.guardrails(),
                    spec.originatingRequest());
            layers.add("guardrails");
        }

        String structuredSchemaText = null;
        var hasStructured = spec.responseType() != null && spec.responseType() != Void.class;
        if (hasStructured) {
            var parser = StructuredOutputParser.resolve();
            target = new StructuredOutputCapturingSession(target, parser, spec.responseType());
            structuredSchemaText = parser.schemaInstructions(spec.responseType());
            layers.add("structured-output");
        }

        String confidenceCueText = null;
        if (spec.confidence() != null && !hasStructured) {
            target = new ConfidenceCapturingSession(target, spec.confidence());
            confidenceCueText = spec.confidence().effectiveCue();
            layers.add("confidence");
        }

        return new Composed(target, budgetSession, structuredSchemaText,
                confidenceCueText, List.copyOf(layers));
    }
}
