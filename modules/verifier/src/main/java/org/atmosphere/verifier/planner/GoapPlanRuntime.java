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
package org.atmosphere.verifier.planner;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * An {@link AgentRuntime} that produces tool-call workflows by deterministic
 * {@linkplain GoapPlanner GOAP search} instead of by prompting an LLM. It is a
 * drop-in plan source for {@link org.atmosphere.verifier.PlanAndVerify}: its
 * {@link #generate} emits the same workflow JSON an LLM plan-mode runtime
 * would, so the identical static verifier chain runs over the result.
 *
 * <p>Constructed with a fixed action domain, an initial world state, and a
 * {@code goalResolver} that maps the incoming request text to the set of goal
 * predicates to plan toward. Because the planner only ever assembles actions
 * that advance the declared goal, it cannot be steered into an off-goal plan
 * (e.g. an exfiltration step that no goal predicate requires) — the
 * deterministic analogue of the verifier rejecting a malicious LLM plan.</p>
 *
 * <p>Priority is {@code -1} so it is never auto-selected by the
 * {@code ServiceLoader} discovery; applications wire it explicitly where a
 * deterministic planner is wanted.</p>
 */
public final class GoapPlanRuntime implements AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(GoapPlanRuntime.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<GoapAction> actions;
    private final Set<String> initialState;
    private final Function<String, Set<String>> goalResolver;
    private final GoapPlanner planner;

    public GoapPlanRuntime(List<GoapAction> actions, Set<String> initialState,
                           Function<String, Set<String>> goalResolver) {
        this(actions, initialState, goalResolver, new GoapPlanner());
    }

    public GoapPlanRuntime(List<GoapAction> actions, Set<String> initialState,
                           Function<String, Set<String>> goalResolver, GoapPlanner planner) {
        this.actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        this.initialState = Set.copyOf(Objects.requireNonNull(initialState, "initialState"));
        this.goalResolver = Objects.requireNonNull(goalResolver, "goalResolver");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    public String name() {
        return "goap-plan";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return -1; // never auto-selected — wired explicitly where determinism is wanted
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // No model to configure — planning is fully local.
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of();
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        session.send(generate(context));
        session.complete();
    }

    @Override
    public String generate(AgentExecutionContext context) {
        var goalText = context.message() != null ? context.message() : "";
        var goalState = goalResolver.apply(goalText);
        var goal = goalText.isBlank() ? "Deterministic plan" : goalText;
        var workflow = planner.plan(goal, initialState, goalState, actions)
                .orElseGet(() -> {
                    logger.info("GOAP found no plan for goal predicates {}; emitting empty plan", goalState);
                    return new Workflow(goal, List.of());
                });
        return toPlanJson(workflow);
    }

    /**
     * Serialize a workflow to the flat plan-JSON shape
     * {@code WorkflowJsonParser} expects ({@code {goal, steps:[{label, toolName,
     * arguments, resultBinding}]}}). Symbolic argument values are plain
     * {@code "@binding"} strings here; the parser re-binds them to
     * {@code SymRef}s on the way back in.
     */
    private static String toPlanJson(Workflow workflow) {
        var root = new LinkedHashMap<String, Object>();
        root.put("goal", workflow.goal());
        var steps = new ArrayList<Object>(workflow.steps().size());
        for (var step : workflow.steps()) {
            var node = (ToolCallNode) step.node();
            var stepMap = new LinkedHashMap<String, Object>();
            stepMap.put("label", step.label());
            stepMap.put("toolName", node.toolName());
            stepMap.put("arguments", node.arguments());
            if (node.hasResultBinding()) {
                stepMap.put("resultBinding", node.resultBinding());
            }
            steps.add(stepMap);
        }
        root.put("steps", steps);
        return MAPPER.writeValueAsString(root);
    }
}
