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

import java.time.Duration;
import java.util.List;

/**
 * The captured outcome of one {@link CodeSandbox#exec(SandboxCommand)} call.
 *
 * <p>A non-zero {@link #exitCode()} is a normal result the model reads and
 * adapts to — it is <em>not</em> a sandbox failure (that surfaces as a
 * {@link SandboxException}). {@link #timedOut()} reports that the per-command
 * budget elapsed and the process was killed.</p>
 *
 * @param exitCode the process exit code ({@code 0} = success)
 * @param stdout   captured standard output (possibly truncated)
 * @param stderr   captured standard error (possibly truncated)
 * @param truncated whether output was clipped at the configured byte cap
 *                  (Correctness Invariant #3 — Backpressure)
 * @param timedOut whether the command was killed for exceeding its timeout
 * @param duration wall-clock time the command ran before completing or being killed
 * @param artifacts binary artifacts the round produced (screenshots, files);
 *                  defensively copied to an immutable list
 */
public record SandboxResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        boolean timedOut,
        Duration duration,
        List<SandboxArtifact> artifacts) {

    public SandboxResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        duration = duration == null ? Duration.ZERO : duration;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    /** Whether the command completed successfully (exit code {@code 0}). */
    public boolean ok() {
        return exitCode == 0;
    }
}
