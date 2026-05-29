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
package org.atmosphere.ai.code;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ToolLoopPolicy;
import org.atmosphere.ai.llm.ToolLoopPolicies;
import org.atmosphere.ai.tool.ToolDefinition;

/**
 * The single integration seam for the code-as-action feature. Holds the
 * (gated) {@link CodeSandboxFactory} and exposes the three things the pipeline
 * needs to wire it in:
 *
 * <ol>
 *   <li>{@link #isEnabled()} — whether to advertise the tool at all (reflects
 *       confirmed runtime state via the factory, not just config);</li>
 *   <li>{@link #tool()} — the {@code code_exec} {@link ToolDefinition} to add to
 *       the registry;</li>
 *   <li>{@link #install(StreamingSession, String)} — provision a per-session
 *       sandbox and bind its teardown to the session's terminal path; the caller
 *       puts the returned sandbox into the session's injectables so the tool
 *       executor can reach it.</li>
 * </ol>
 *
 * <p>And {@link #withCodeActionLoop(AgentExecutionContext)} lifts the tool-loop
 * ceiling, since the code-as-action pattern needs many write→run→observe rounds
 * rather than the default five.</p>
 */
public final class CodeExecSupport {

    /**
     * Round ceiling for the code-as-action loop. The default tool-loop policy
     * allows five iterations, which is far too few for an agent that iterates by
     * writing, running, observing, and revising code.
     */
    public static final int CODE_ACTION_MAX_ROUNDS = 25;

    private final CodeSandboxFactory factory;

    public CodeExecSupport(CodeSandboxFactory factory) {
        this.factory = factory == null ? CodeSandboxFactory.disabled() : factory;
    }

    /** Resolve from {@code org.atmosphere.ai.code.*} configuration (default deny). */
    public static CodeExecSupport fromSystemProperties() {
        return new CodeExecSupport(
                CodeSandboxFactory.fromConfig(CodeSandboxConfig.fromSystemProperties()));
    }

    private static volatile CodeExecSupport shared;

    /**
     * The process-wide instance resolved from system properties, shared by the
     * tool-registration and session-install sites so they observe one gating
     * decision and one engine-probe cache. Lazily initialized.
     */
    public static CodeExecSupport shared() {
        var instance = shared;
        if (instance == null) {
            synchronized (CodeExecSupport.class) {
                instance = shared;
                if (instance == null) {
                    instance = fromSystemProperties();
                    shared = instance;
                }
            }
        }
        return instance;
    }

    /**
     * Whether the {@code code_exec} tool should be offered: enabled in config and
     * confirmed runnable at runtime (Correctness Invariant #5 — Runtime Truth).
     */
    public boolean isEnabled() {
        return factory.isAvailable();
    }

    /** The tool definition to register when {@link #isEnabled()}. */
    public ToolDefinition tool() {
        return CodeExecTool.definition();
    }

    /**
     * Provision a lazy per-session sandbox and bind its teardown to the session's
     * terminal path (success, error, cancel). Returns the sandbox so the caller
     * can register it as the {@link CodeSandbox} injectable; returns {@code null}
     * when the feature is disabled, so callers can guard with a single null check.
     *
     * @param session   the agent-backed session (must support {@code onTerminate})
     * @param sessionId stable id used to label the sandbox
     * @return the session-scoped sandbox, or {@code null} when disabled
     */
    public CodeSandbox install(StreamingSession session, String sessionId) {
        if (!isEnabled() || session == null) {
            return null;
        }
        var sandbox = new SessionSandbox(factory, sessionId);
        // Bind teardown first: even if the model never calls code_exec, the
        // (lazy, never-provisioned) sandbox is closed harmlessly on termination.
        session.onTerminate(sandbox);
        return sandbox;
    }

    /**
     * Return a context with the code-action tool-loop ceiling attached, lifting
     * the default five-round limit to {@link #CODE_ACTION_MAX_ROUNDS}.
     */
    public AgentExecutionContext withCodeActionLoop(AgentExecutionContext context) {
        return ToolLoopPolicies.attach(context,
                ToolLoopPolicy.maxIterations(CODE_ACTION_MAX_ROUNDS));
    }
}
