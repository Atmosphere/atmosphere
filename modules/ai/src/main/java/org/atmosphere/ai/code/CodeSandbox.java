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

/**
 * A single-session, isolated environment in which model-generated code is
 * executed. This is the <em>action substrate</em> behind the code-as-action
 * tool: instead of negotiating many fine-grained tool calls, the model emits a
 * block of code, the sandbox runs it, and the resulting logs / exit code /
 * artifacts (screenshots, files) are streamed back as observations.
 *
 * <p>One sandbox is created lazily per {@link org.atmosphere.ai.StreamingSession}
 * and reused across every code-execution round in that run. The session owns the
 * sandbox's lifecycle: when the session completes, errors, or is cancelled, the
 * sandbox is {@linkplain #close() closed}, which tears down the underlying
 * isolation boundary (e.g. the ephemeral container). This binds the substrate's
 * lifecycle to a single terminal-path owner — Correctness Invariants #1
 * (Ownership) and #2 (Terminal Path Completeness).</p>
 *
 * <p>Executing model-generated code is the largest boundary surface Atmosphere
 * exposes (Correctness Invariant #4). Implementations MUST isolate execution
 * (no host filesystem, no host network unless explicitly allowed) and MUST be
 * gated behind explicit opt-in (Correctness Invariant #6 — default deny). See
 * {@link CodeSandboxConfig}.</p>
 *
 * <p>Implementations are not required to be thread-safe across concurrent
 * {@link #exec(SandboxCommand)} calls; the code-action loop is sequential
 * (write → run → observe → repeat). {@link #close()} MUST be idempotent and MUST
 * interrupt any in-flight execution.</p>
 */
public interface CodeSandbox extends AutoCloseable {

    /**
     * Stable identifier for this sandbox. Typically derived from the owning
     * session id so logs and metrics can be correlated.
     *
     * @return the sandbox id; never {@code null}
     */
    String id();

    /**
     * Whether the sandbox is provisioned and able to accept
     * {@link #exec(SandboxCommand)} calls. Returns {@code false} once
     * {@link #close()} has run.
     *
     * @return {@code true} if execution can proceed
     */
    boolean isReady();

    /**
     * Execute a block of code and return its captured result. The call blocks
     * until the code completes, the per-command timeout elapses, or the sandbox
     * is closed.
     *
     * <p>A non-zero {@link SandboxResult#exitCode()} is a normal result, not an
     * exception — the model is expected to read the logs and adapt. A
     * {@link SandboxException} is thrown only when the sandbox itself could not
     * run the command (substrate gone, provisioning failed, hard timeout kill).</p>
     *
     * <p>Output is bounded ({@link CodeSandboxConfig#maxOutputBytes()}); when the
     * cap is exceeded the result is truncated and {@link SandboxResult#truncated()}
     * is {@code true} (Correctness Invariant #3 — Backpressure).</p>
     *
     * @param command the language + code + per-command timeout to run
     * @return the captured execution result; never {@code null}
     * @throws SandboxException if the sandbox could not execute the command
     */
    SandboxResult exec(SandboxCommand command) throws SandboxException;

    /**
     * Tear down the sandbox and release all resources it created (container,
     * workspace, processes). MUST be idempotent and MUST interrupt any in-flight
     * {@link #exec(SandboxCommand)}. After this returns, {@link #isReady()} is
     * {@code false}.
     */
    @Override
    void close();
}
