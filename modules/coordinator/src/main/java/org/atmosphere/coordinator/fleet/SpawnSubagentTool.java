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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.fs.FileSystemTools;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.FileSystemAgentPlanStore;
import org.atmosphere.ai.plan.PlanningTools;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Built-in LLM-callable tool that spawns an ephemeral <em>general-purpose</em>
 * subagent with an isolated context and workspace, runs a single self-contained
 * subtask, and returns the subagent's final report.
 *
 * <p>This is the dynamic counterpart to {@link DelegateTaskTool}: where
 * {@code delegate_task} routes to a <em>pre-declared</em> fleet member,
 * {@code task} creates a fresh worker on demand with a caller-generated prompt —
 * the parity primitive for LangChain deepagents' {@code task} tool. Registered
 * by the harness DELEGATION feature on a {@code @Coordinator} alongside
 * {@code delegate_task}.</p>
 *
 * <p><strong>Isolation.</strong> Each spawn gets a fresh conversation id and its
 * own plan store plus bounded file workspace under a per-spawn temp root, so the
 * subagent's {@code write_todos} / file tools never touch the parent's plan or
 * files (deepagents' "fresh context window"). The workspace is removed when the
 * subagent returns — only the final report crosses back.</p>
 *
 * <p><strong>Safety invariants.</strong></p>
 * <ul>
 *   <li><b>Governance (Inv #6).</b> The subtask prompt is evaluated against the
 *       installed governance policies (pre-admission, fail-closed) before any
 *       dispatch — the same policy chain {@link GovernanceFleetInterceptor}
 *       applies to the fleet dispatch edge.</li>
 *   <li><b>Backpressure (Inv #3).</b> Recursion depth and per-request spawn
 *       count are bounded via a {@link ThreadLocal}; beyond the caps the tool
 *       refuses rather than spawning, and a subagent at max depth is not given
 *       the {@code task} tool, so spawning cannot recurse without bound.</li>
 *   <li><b>Terminal paths (Inv #2).</b> The run is time-bounded; on timeout the
 *       {@link ExecutionHandle} is cancelled, and the session is completed and
 *       the temp workspace removed on every exit path.</li>
 *   <li><b>Ownership (Inv #1).</b> The tool owns everything it creates (stores,
 *       session, temp dir) and releases it; it never touches caller
 *       resources.</li>
 * </ul>
 */
public final class SpawnSubagentTool {

    private static final Logger logger = LoggerFactory.getLogger(SpawnSubagentTool.class);

    /** The only subagent type this tool spawns; named types belong to {@code delegate_task}. */
    static final String GENERAL_PURPOSE = "general-purpose";

    /** Maximum nesting: a subagent may decompose one level, but not spawn forever. */
    static final int MAX_DEPTH = 2;

    /** Maximum total spawns rooted at one top-level request (spawn-storm guard). */
    static final int MAX_SPAWNS_PER_REQUEST = 8;

    /** Wall-clock bound for a single subagent run. */
    static final Duration SPAWN_TIMEOUT = Duration.ofSeconds(120);

    private static final String SUBAGENT_SYSTEM_PROMPT =
            "You are a focused general-purpose subagent working in an isolated workspace. "
            + "Complete the single task you are given end to end, using your planning and "
            + "file tools as needed, then reply with a concise final report of what you "
            + "found or produced. You cannot ask follow-up questions — make reasonable "
            + "assumptions and finish.";

    /** [0] = current nesting depth, [1] = spawns used by this request. Per dispatch thread. */
    private static final ThreadLocal<int[]> BUDGET = ThreadLocal.withInitial(() -> new int[2]);

    private final List<GovernancePolicy> policies;
    private final Supplier<AgentRuntime> runtime;
    private final Path spawnRootBase;
    private final Duration timeout;

    /**
     * @param policies the installed governance policies (pre-admission is
     *                 evaluated against these); may be empty, never null.
     */
    public SpawnSubagentTool(List<GovernancePolicy> policies) {
        this(policies, AgentRuntimeResolver::resolve, null, SPAWN_TIMEOUT);
    }

    // visible for testing — inject a stub runtime, a fixed workspace root, and a short timeout
    SpawnSubagentTool(List<GovernancePolicy> policies, Supplier<AgentRuntime> runtime,
                      Path spawnRootBase, Duration timeout) {
        this.policies = List.copyOf(policies == null ? List.of() : policies);
        this.runtime = runtime;
        this.spawnRootBase = spawnRootBase;
        this.timeout = timeout;
    }

    @AiTool(name = "task",
            description = "Spawn an ephemeral general-purpose subagent with an isolated context and "
                    + "workspace to handle a focused, self-contained subtask, and return its final "
                    + "report. Use for research or analysis that should not clutter the main "
                    + "conversation. For a named specialist that already exists in the fleet, use "
                    + "delegate_task instead.")
    public String task(
            @Param(value = "description",
                    description = "The focused subtask, written as a complete standalone instruction "
                            + "— the subagent cannot ask follow-up questions.") String description,
            @Param(value = "subagent_type", required = false,
                    description = "Subagent type; only 'general-purpose' is supported (the default).")
                    String subagentType) {

        if (description == null || description.isBlank()) {
            return "task failed: 'description' is required.";
        }
        var type = (subagentType == null || subagentType.isBlank()) ? GENERAL_PURPOSE : subagentType.trim();
        if (!GENERAL_PURPOSE.equalsIgnoreCase(type)) {
            return "task failed: unknown subagent_type '" + type + "'. Only 'general-purpose' is "
                    + "supported — use delegate_task for a named fleet specialist.";
        }

        var budget = BUDGET.get();
        if (budget[0] >= MAX_DEPTH) {
            return "task refused: maximum subagent nesting depth (" + MAX_DEPTH + ") reached — "
                    + "complete this step inline instead of spawning again.";
        }
        if (budget[1] >= MAX_SPAWNS_PER_REQUEST) {
            return "task refused: maximum subagent spawns (" + MAX_SPAWNS_PER_REQUEST + ") for this "
                    + "request reached.";
        }

        var denial = governanceDenial(description);
        if (denial != null) {
            return "task denied by policy: " + denial;
        }

        budget[0]++;
        budget[1]++;
        try {
            return runIsolated(description);
        } catch (RuntimeException e) {
            logger.warn("task subagent failed: {}", e.toString());
            return "task failed: " + e.getMessage();
        } finally {
            budget[0]--;
        }
    }

    /**
     * Pre-admission governance over the subtask prompt, fail-closed, mirroring
     * {@link GovernanceFleetInterceptor}. Returns a denial reason, or {@code null}
     * when the spawn is admitted.
     */
    private String governanceDenial(String description) {
        if (policies.isEmpty()) {
            return null;
        }
        var request = new AiRequest(description, null, null, null, null, null, null,
                Map.of("subagent.spawn", GENERAL_PURPOSE), null);
        var context = PolicyContext.preAdmission(request);
        for (var policy : policies) {
            PolicyDecision decision;
            try {
                decision = policy.evaluate(context);
            } catch (RuntimeException e) {
                logger.error("GovernancePolicy {} threw during subagent spawn — fail-closed",
                        policy.name(), e);
                return "policy '" + policy.name() + "' evaluation failed";
            }
            switch (decision) {
                case PolicyDecision.Admit ignored -> { /* next policy */ }
                case PolicyDecision.Prefer prefer -> {
                    // Soft governance: advisory only — the spawn proceeds (logged, not enforced;
                    // a hard block would use Deny). Mirrors GovernanceFleetInterceptor.
                    logger.debug("Subagent spawn preferred alternative from {} ({}): {}",
                            policy.name(), prefer.reason(), prefer.preferred());
                }
                case PolicyDecision.Deny deny -> {
                    logger.info("Subagent spawn denied by {}: {}", policy.name(), deny.reason());
                    return deny.reason();
                }
                case PolicyDecision.Transform transform ->
                        context = PolicyContext.preAdmission(transform.modifiedRequest());
            }
        }
        return null;
    }

    private String runIsolated(String description) {
        var conversationId = UUID.randomUUID().toString();
        var agentId = "subagent-" + conversationId.substring(0, 8);
        Path root = null;
        try {
            root = spawnRootBase != null
                    ? Files.createDirectories(spawnRootBase.resolve(conversationId))
                    : Files.createTempDirectory("atmosphere-subagent-");

            var planStore = new FileSystemAgentPlanStore(root);
            var fsProvider = new AgentFileSystemProvider(root, AgentFileSystem.Limits.defaults());

            var tools = new ArrayList<ToolDefinition>();
            tools.add(PlanningTools.writeTodosTool(agentId));
            tools.addAll(FileSystemTools.all());

            var injectables = Map.<Class<?>, Object>of(
                    AgentPlanStore.class, planStore,
                    AgentFileSystemProvider.class, fsProvider,
                    ToolScopes.ConversationScope.class, new ToolScopes.ConversationScope(conversationId));

            var session = new SubagentSession(conversationId, injectables);

            // 16-arg context shim: no multimodal parts, annotation-driven approval
            // policy (the isolated floor tools carry no @RequiresApproval), fresh
            // isolated conversation identity so plan/file state does not bleed.
            var context = new AgentExecutionContext(
                    description, SUBAGENT_SYSTEM_PROMPT, null,
                    agentId, conversationId, "subagent", conversationId,
                    tools, null, null, List.of(), Map.of(), List.of(),
                    null, null, List.of());

            // The subagent runs on a fresh virtual thread, so the recursion
            // budget (a ThreadLocal) must be seeded from this thread's
            // accumulated depth/count — otherwise a nested task() call would
            // see a fresh [0,0] and the depth bound would never trip.
            var seed = BUDGET.get().clone();
            var handleRef = new AtomicReference<ExecutionHandle>();
            var worker = Thread.ofVirtual().name(agentId).start(() -> {
                var workerBudget = BUDGET.get();
                workerBudget[0] = seed[0];
                workerBudget[1] = seed[1];
                try {
                    handleRef.set(runtime.get().executeWithHandle(context, session));
                } catch (Throwable t) {
                    session.error(t);
                }
            });

            if (!session.await(timeout)) {
                var handle = handleRef.get();
                if (handle != null) {
                    handle.cancel();
                }
                worker.interrupt();
                logger.info("Subagent {} timed out after {}s", agentId, timeout.toSeconds());
                return "task did not complete within " + timeout.toSeconds() + " seconds.";
            }
            if (session.failed()) {
                var cause = session.failure();
                return "task failed: " + (cause != null && cause.getMessage() != null
                        ? cause.getMessage() : "unknown error");
            }
            var text = session.text().strip();
            return text.isEmpty() ? "task completed with no output." : text;
        } catch (IOException e) {
            return "task failed: could not create an isolated workspace: " + e.getMessage();
        } finally {
            deleteQuietly(root);
        }
    }

    private static void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    logger.trace("could not delete subagent temp path {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.trace("could not clean subagent workspace {}: {}", root, e.getMessage());
        }
    }

    /**
     * Minimal collecting session for an isolated subagent run: accumulates the
     * text stream, exposes the fixed injectables the floor tools resolve their
     * stores from, and latches on the first terminal signal. Its own
     * {@link CollectingSession} equivalent cannot be reused because that class is
     * final and does not expose {@link #injectables()}.
     */
    private static final class SubagentSession implements StreamingSession {
        private final String sessionId;
        private final Map<Class<?>, Object> injectables;
        private final StringBuilder buffer = new StringBuilder();
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        private volatile Throwable failure;
        private volatile boolean closed;

        SubagentSession(String sessionId, Map<Class<?>, Object> injectables) {
            this.sessionId = sessionId;
            this.injectables = injectables;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public Map<Class<?>, Object> injectables() {
            return injectables;
        }

        @Override
        public synchronized void send(String text) {
            if (text != null) {
                buffer.append(text);
            }
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // isolated run — metadata does not cross back to the parent
        }

        @Override
        public void progress(String message) {
            // isolated run — intermediate progress is not surfaced
        }

        @Override
        public void emit(AiEvent event) {
            // isolated run — structured events do not cross back; only the
            // final text report returns to the parent (deepagents semantics).
        }

        @Override
        public void complete() {
            closed = true;
            latch.countDown();
        }

        @Override
        public void complete(String summary) {
            send(summary);
            complete();
        }

        @Override
        public void error(Throwable t) {
            this.failure = t;
            closed = true;
            latch.countDown();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        boolean await(Duration timeout) {
            try {
                return latch.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean failed() {
            return failure != null;
        }

        Throwable failure() {
            return failure;
        }

        synchronized String text() {
            return buffer.toString();
        }
    }
}
