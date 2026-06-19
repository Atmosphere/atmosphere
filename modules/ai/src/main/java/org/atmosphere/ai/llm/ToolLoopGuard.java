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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cross-runtime enforcer for {@link ToolLoopPolicy}. Counts {@code onModelStart}
 * lifecycle events on a per-execute basis and, when the policy's iteration cap
 * is exceeded, terminates the streaming session via
 * {@link StreamingSession#error(Throwable)} with a
 * {@link ToolLoopPolicy.ToolLoopExhaustedException}.
 *
 * <p>Why a guard instead of native upstream knobs everywhere: every framework
 * runtime exposes its tool loop differently (Spring AI delegates to its
 * {@code ChatModel}, ADK uses {@code BeforeModelCallback}s, Embabel surfaces a
 * {@code ToolLoopInspector}, Semantic Kernel hides
 * {@code ToolCallBehavior.maxAutoInvokeAttempts} behind a package-private
 * constructor). A wire-level enforcer driven by the
 * {@link AgentLifecycleListener#onModelStart} hook works uniformly because
 * every framework runtime now fires that hook. The user observes a hard cap
 * on every runtime regardless of whether the upstream library exposed a
 * per-request knob.</p>
 *
 * <h2>Semantics</h2>
 *
 * <p>For {@link ToolLoopPolicy.OnMaxIterations#FAIL}: when {@code onModelStart}
 * fires for the (cap + 1)<sup>st</sup> time, the guard atomically transitions
 * to a tripped state and calls {@code session.error(...)}. The session enters
 * its closed state (see {@link org.atmosphere.ai.DefaultStreamingSession});
 * any subsequent {@code session.send / sendMetadata / progress} from the
 * runtime are dropped at the wire layer. The runtime may continue executing
 * upstream — the guard cannot reach into Spring AI's {@code ChatModel} or
 * Embabel's planner to abort their internal flow — but the user-observable
 * result is a hard cap on iterations.</p>
 *
 * <p>For {@link ToolLoopPolicy.OnMaxIterations#COMPLETE_WITHOUT_TOOLS}: the
 * guard logs once and otherwise no-ops. This overflow mode means "stop tool
 * calling and complete with the text we have" which can only be synthesized
 * from <em>inside</em> the tool loop. The Built-in runtime
 * ({@link OpenAiCompatibleClient}) honors it natively because it owns its
 * loop. Koog honors it via {@code AIAgent.maxIterations × 2}. For framework
 * runtimes whose loop is not addressable from outside, the upstream library's
 * own default cap takes over — callers who need a hard cap on every runtime
 * should use {@link ToolLoopPolicy#strict(int)} instead.</p>
 *
 * <h2>Counting model</h2>
 *
 * <p>The guard counts {@code onModelStart} invocations within a single execute.
 * For a non-graph runtime, each invocation is one tool-loop round (LLM call
 * → tool execution → next LLM call), matching {@link ToolLoopPolicy#maxIterations}
 * exactly. For graph-based runtimes (Embabel) where one execute spans multiple
 * agent steps each with their own LLM call, the cap effectively applies to
 * total LLM calls per execute — strictly stronger than the rounds-only
 * interpretation, which is the safer direction for a guard.</p>
 *
 * <h2>Installation</h2>
 *
 * <p>Runtime authors call {@link #installIfPresent} at the top of their
 * {@code execute} (and {@code executeWithHandle}) to inject the guard into
 * the per-execute listener list. {@link AbstractAgentRuntime#execute} does
 * this automatically; Kotlin runtimes that implement
 * {@link org.atmosphere.ai.AgentRuntime} directly
 * ({@code KoogAgentRuntime}, {@code EmbabelAgentRuntime}) call it explicitly
 * because they do not inherit the base class.</p>
 *
 * @since 4.0
 */
public final class ToolLoopGuard implements AgentLifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(ToolLoopGuard.class);

    private final String runtimeName;
    private final ToolLoopPolicy policy;
    private final StreamingSession session;
    private final AtomicInteger modelCallCount = new AtomicInteger(0);
    private final AtomicBoolean tripped = new AtomicBoolean(false);

    public ToolLoopGuard(String runtimeName, ToolLoopPolicy policy, StreamingSession session) {
        this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public void onModelStart(String model, int messageCount, int toolCount) {
        int count = modelCallCount.incrementAndGet();
        if (count <= policy.maxIterations()) {
            return;
        }
        if (!tripped.compareAndSet(false, true)) {
            return;
        }
        switch (policy.onMaxIterations()) {
            case FAIL -> {
                logger.info("ToolLoopPolicy.FAIL tripped for {} after {} model calls (cap={}), aborting session via session.error(...)",
                        runtimeName, count, policy.maxIterations());
                session.error(new ToolLoopPolicy.ToolLoopExhaustedException(policy.maxIterations()));
            }
            case COMPLETE_WITHOUT_TOOLS -> logger.info(
                    "ToolLoopPolicy.COMPLETE_WITHOUT_TOOLS reached for {} after {} model calls (cap={}); guard is a no-op for this overflow mode "
                            + "— runtime falls through to native default cap (use ToolLoopPolicy.strict(N) for a hard cap on every runtime)",
                    runtimeName, count, policy.maxIterations());
        }
    }

    /**
     * Read the per-request policy from {@code context} and, when present, return
     * a copy of the context with a fresh guard listener appended. Pass-through
     * when no policy is attached. Idempotent only across distinct contexts —
     * each execute should call this exactly once with its own session.
     */
    public static AgentExecutionContext installIfPresent(String runtimeName,
                                                         AgentExecutionContext context,
                                                         StreamingSession session) {
        var policy = ToolLoopPolicies.from(context);
        if (policy == null) {
            return context;
        }
        var guard = new ToolLoopGuard(runtimeName, policy, session);
        var existing = context.listeners();
        var combined = new ArrayList<AgentLifecycleListener>(existing.size() + 1);
        combined.addAll(existing);
        combined.add(guard);
        return context.withListeners(List.copyOf(combined));
    }

    /**
     * In-loop cap decision shared by the SSE-driver runtimes (Anthropic,
     * Cohere) and the LangChain4j streaming handler. Unlike the listener-based
     * guard above — which can only close the session at the wire layer once a
     * framework's own loop overruns — this is called from <em>inside</em> a
     * client's tool loop, before the next model→tool→model round is dispatched,
     * so it can actually stop the loop and honor the per-request
     * {@link ToolLoopPolicy} exactly like {@link OpenAiCompatibleClient} does.
     *
     * <p>Counting matches the Built-in runtime: {@code round} is the zero-based
     * count of tool rounds already executed, and the loop continues while
     * {@code round < policy.maxIterations()}. When {@code round} reaches the
     * cap, the FAIL branch is the byte-identical core shared by every driver —
     * {@code session.error(new ToolLoopExhaustedException(cap))} — while the
     * COMPLETE_WITHOUT_TOOLS branch returns control so each driver can apply
     * its own completion shape (Anthropic/Cohere flush nothing and call
     * {@code session.complete()}; LangChain4j flushes the last assistant text
     * via {@code session.complete(text)}).</p>
     *
     * @param round       zero-based count of tool rounds already executed
     * @param policy      the per-request policy (never null — callers pass
     *                    {@link ToolLoopPolicies#fromOrDefault})
     * @param session     the streaming session to terminate on FAIL
     * @param runtimeName provider name for the overflow log line
     * @return the action the caller must take for this round
     */
    public static CapDecision checkRoundCap(int round, ToolLoopPolicy policy,
                                            StreamingSession session, String runtimeName) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(session, "session");
        if (round < policy.maxIterations()) {
            return CapDecision.CONTINUE;
        }
        logger.warn("{} tool loop cap ({}) reached, applying onMaxIterations={}",
                runtimeName, policy.maxIterations(), policy.onMaxIterations());
        return switch (policy.onMaxIterations()) {
            case FAIL -> {
                if (!session.isClosed()) {
                    session.error(new ToolLoopPolicy.ToolLoopExhaustedException(policy.maxIterations()));
                }
                yield CapDecision.FAILED;
            }
            case COMPLETE_WITHOUT_TOOLS -> CapDecision.COMPLETE_WITHOUT_TOOLS;
        };
    }

    /**
     * Outcome of {@link #checkRoundCap}: whether the driver should run another
     * round, terminate by failing the session (already done by the helper), or
     * complete with whatever text it has.
     */
    public enum CapDecision {
        /** Below the cap — run the next round. */
        CONTINUE,
        /** Cap reached with {@link ToolLoopPolicy.OnMaxIterations#FAIL} — the
         *  helper already called {@code session.error(...)}; the driver must
         *  return without completing. */
        FAILED,
        /** Cap reached with
         *  {@link ToolLoopPolicy.OnMaxIterations#COMPLETE_WITHOUT_TOOLS} — the
         *  driver must complete the session with whatever text it has. */
        COMPLETE_WITHOUT_TOOLS
    }

    /** Package-private for test introspection — current count of {@code onModelStart} events seen. */
    int currentCount() {
        return modelCallCount.get();
    }

    /** Package-private for test introspection — whether the guard has fired the cap-hit path. */
    boolean isTripped() {
        return tripped.get();
    }
}
