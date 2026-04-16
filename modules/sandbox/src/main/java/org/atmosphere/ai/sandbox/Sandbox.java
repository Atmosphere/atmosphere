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
package org.atmosphere.ai.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Isolated-execution primitive for tools that must run untrusted code,
 * LLM-generated commands, or data transforms. Runs in a controlled
 * environment with configurable resource limits.
 *
 * <h2>Pluggable backends</h2>
 *
 * Implementations ship in separate modules and are discovered via
 * {@link java.util.ServiceLoader} (see {@link SandboxProvider}). Atmosphere
 * currently ships:
 *
 * <ul>
 *   <li>{@code DockerSandbox} — default for local development and
 *       self-hosted production. Shells out to {@code docker} CLI, uses
 *       resource limits via {@code --cpus} / {@code --memory} / timeouts.</li>
 *   <li>{@code InProcessSandbox} — reference implementation running in a
 *       JVM child process with a tempdir; <b>not</b> a security boundary,
 *       explicitly flagged as dev-only.</li>
 * </ul>
 *
 * <p>Third-party backends (Firecracker, Kata, Vercel Sandbox, E2B, Modal,
 * Blaxel) implement this SPI in their own modules. This keeps the
 * foundation dependency-free.</p>
 *
 * <h2>Terminal-path discipline</h2>
 *
 * {@link AutoCloseable#close()} must be idempotent (Correctness Invariant
 * #2). Implementations tear down the sandbox environment on close; callers
 * MUST use try-with-resources.
 *
 * <h2>Fail-hard on missing backend</h2>
 *
 * When {@code @SandboxTool} declares a backend and that backend is not
 * available ({@code docker} command missing, Docker daemon unreachable),
 * constructors fail fast with a descriptive message. There is no silent
 * in-JVM fallback (per v0.6 plan open question #2).
 */
public interface Sandbox extends AutoCloseable {

    /** Stable identifier for this sandbox instance. */
    String id();

    /** Execute a command inside the sandbox and block for the result. */
    SandboxExec exec(List<String> command, Duration timeout);

    /** Write text to a file inside the sandbox. */
    void writeFile(Path pathInsideSandbox, String content);

    /** Read text from a file inside the sandbox. */
    String readFile(Path pathInsideSandbox);

    /**
     * Expose a TCP port inside the sandbox on the host. Returns the host
     * port the caller should connect to. Optional — implementations may
     * throw {@link UnsupportedOperationException} when they do not expose
     * networking.
     */
    default int expose(int portInsideSandbox) {
        throw new UnsupportedOperationException(
                "This sandbox backend does not support port exposure");
    }

    /**
     * Capture a snapshot of the sandbox filesystem state. Returns a
     * {@link SandboxSnapshot} that can be restored later. Optional.
     */
    default SandboxSnapshot snapshot() {
        throw new UnsupportedOperationException(
                "This sandbox backend does not support snapshots");
    }

    /**
     * Suspend the sandbox to reclaim compute while preserving state. A
     * subsequent {@link #exec} implicitly resumes. Optional.
     */
    default void hibernate() {
        throw new UnsupportedOperationException(
                "This sandbox backend does not support hibernation");
    }

    /**
     * The resource limits this sandbox was created with. Surfaced to the
     * admin UI so operators can see exactly what constraints apply.
     */
    SandboxLimits limits();

    /** Free the sandbox and any resources it holds. Idempotent. */
    @Override
    void close();

    /** Metadata the sandbox was created with. */
    Map<String, String> metadata();
}
