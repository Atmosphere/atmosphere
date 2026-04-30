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
package org.atmosphere.verifier;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.execute.RegistryToolDispatcher;
import org.atmosphere.verifier.execute.ToolDispatcher;
import org.atmosphere.verifier.execute.WorkflowExecutor;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.prompt.PlanPromptBuilder;
import org.atmosphere.verifier.prompt.WorkflowJsonParser;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Plan-and-verify orchestrator: the public entry point for Meijer-style
 * static verification of LLM-emitted tool-call workflows.
 *
 * <p>Wires together every other component in the module:</p>
 * <ol>
 *   <li>{@link PlanPromptBuilder} — generates a system prompt that asks the
 *       LLM to emit a workflow JSON instead of calling tools directly,
 *       advertising only policy-permitted tools.</li>
 *   <li>{@link AgentRuntime#generate} — invokes the resolved runtime with
 *       the user's goal and the plan-mode system prompt.</li>
 *   <li>{@link WorkflowJsonParser} — parses the LLM's JSON output into the
 *       sealed {@link Workflow} AST, converting {@code "@name"} markers
 *       into {@code SymRef}s.</li>
 *   <li>The {@link PlanVerifier} chain (sorted by priority) — runs every
 *       static check; a single violation aborts the run.</li>
 *   <li>{@link WorkflowExecutor} — dispatches the verified plan
 *       step-by-step, resolving symbolic references against accumulated
 *       bindings.</li>
 * </ol>
 *
 * <p>Two flavors of construction are exposed:</p>
 * <ul>
 *   <li>{@link #withDefaults} discovers verifiers via
 *       {@link ServiceLoader} (the production path).</li>
 *   <li>The full constructor accepts an explicit verifier list so tests
 *       can pin the chain without depending on classpath services.</li>
 * </ul>
 *
 * <p><strong>Correctness invariant #2 (terminal-path completeness)</strong>
 * — every exit path from {@link #run} is typed: success returns the env
 * map; verification refusal throws
 * {@link PlanVerificationException} carrying the offending workflow plus
 * the full violation list; tool failure surfaces a
 * {@link org.atmosphere.verifier.execute.WorkflowExecutionException} with
 * partial-env. There is no path that runs a tool from an unverified plan.</p>
 *
 * <p><strong>Correctness invariant #1 (ownership)</strong> — this class
 * does not own its dependencies; it never closes the runtime, registry, or
 * dispatcher it was given. Lifecycle is the caller's.</p>
 */
public final class PlanAndVerify {

    private static final Logger logger = LoggerFactory.getLogger(PlanAndVerify.class);

    private final AgentRuntime runtime;
    private final ToolRegistry registry;
    private final Policy policy;
    private final List<PlanVerifier> verifiers;
    private final PlanPromptBuilder promptBuilder;
    private final WorkflowJsonParser parser;
    private final WorkflowExecutor executor;

    /**
     * Construct with explicit verifier list and default
     * {@link RegistryToolDispatcher}. Use this when tests need to pin the
     * verifier chain.
     */
    public PlanAndVerify(AgentRuntime runtime,
                         ToolRegistry registry,
                         Policy policy,
                         List<PlanVerifier> verifiers) {
        this(runtime, registry, policy, verifiers, new RegistryToolDispatcher(registry));
    }

    /**
     * Full constructor — takes an explicit verifier list and a custom
     * {@link ToolDispatcher} (e.g.,
     * {@code GatedToolDispatcher} introduced in Phase 2 for HITL approval).
     */
    public PlanAndVerify(AgentRuntime runtime,
                         ToolRegistry registry,
                         Policy policy,
                         List<PlanVerifier> verifiers,
                         ToolDispatcher dispatcher) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(verifiers, "verifiers");
        Objects.requireNonNull(dispatcher, "dispatcher");
        this.verifiers = sortByPriority(verifiers);
        this.parser = new WorkflowJsonParser();
        this.promptBuilder = new PlanPromptBuilder(policy, registry, parser);
        this.executor = new WorkflowExecutor(dispatcher);
    }

    /**
     * Discover verifiers via {@link ServiceLoader} — the production path.
     */
    public static PlanAndVerify withDefaults(AgentRuntime runtime,
                                             ToolRegistry registry,
                                             Policy policy) {
        return new PlanAndVerify(runtime, registry, policy, discoverVerifiers());
    }

    /**
     * Step 1 in isolation: ask the LLM to plan, parse the result, return
     * the AST. Useful for previewing what the LLM would do before
     * verification or for offline plan inspection.
     */
    public Workflow plan(String userGoal) {
        Objects.requireNonNull(userGoal, "userGoal");
        String systemPrompt = promptBuilder.build(userGoal);
        var context = new AgentExecutionContext(
                userGoal,         // message
                systemPrompt,     // systemPrompt
                null,             // model — runtime default
                null,             // agentId
                null,             // sessionId
                null,             // userId
                null,             // conversationId
                List.of(),        // tools — INTENTIONALLY EMPTY: plan mode
                null,             // toolTarget
                null,             // memory
                List.of(),        // contextProviders
                Map.of(),         // metadata
                List.of(),        // history
                null,             // responseType
                null);            // approvalStrategy
        String raw = runtime.generate(context);
        if (logger.isTraceEnabled()) {
            logger.trace("LLM plan output:\n{}", raw);
        }
        return parser.parse(raw);
    }

    /**
     * Step 2 in isolation: run every verifier in priority order against
     * a workflow + the policy/registry pair this orchestrator was
     * constructed with. Aggregates per-verifier results via
     * {@link VerificationResult#merge}.
     */
    public VerificationResult verify(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        VerificationResult acc = VerificationResult.ok();
        for (PlanVerifier v : verifiers) {
            VerificationResult result = v.verify(workflow, policy, registry);
            acc = acc.merge(result);
        }
        return acc;
    }

    /**
     * End-to-end: plan → verify → execute. The pipeline aborts on the
     * first failed stage; the typed exception identifies which stage.
     *
     * @param userGoal   natural-language goal for the LLM to encode.
     * @param initialEnv pre-bound variables visible to step 0 (e.g. user
     *                   email address). Defensive copy is taken.
     * @return immutable env map after every step has fired.
     * @throws PlanVerificationException if the verifier chain rejects.
     * @throws org.atmosphere.verifier.execute.WorkflowExecutionException
     *         on tool failure during execution.
     */
    public Map<String, Object> run(String userGoal, Map<String, Object> initialEnv) {
        Objects.requireNonNull(userGoal, "userGoal");
        Objects.requireNonNull(initialEnv, "initialEnv");
        Workflow workflow = plan(userGoal);
        VerificationResult result = verify(workflow);
        if (!result.isOk()) {
            throw new PlanVerificationException(workflow, result);
        }
        return executor.run(workflow, initialEnv);
    }

    /** @return defensively-copied list of the verifiers in priority order. */
    public List<PlanVerifier> verifiers() {
        return verifiers;
    }

    private static List<PlanVerifier> sortByPriority(List<PlanVerifier> input) {
        var copy = new ArrayList<>(input);
        copy.sort(Comparator.comparingInt(PlanVerifier::priority));
        return List.copyOf(copy);
    }

    private static List<PlanVerifier> discoverVerifiers() {
        var found = new ArrayList<PlanVerifier>();
        for (PlanVerifier v : ServiceLoader.load(PlanVerifier.class)) {
            found.add(v);
        }
        return found;
    }
}
