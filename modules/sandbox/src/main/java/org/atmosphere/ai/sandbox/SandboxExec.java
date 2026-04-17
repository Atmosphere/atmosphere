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

import java.time.Duration;

/**
 * Result of a single command execution inside a sandbox.
 *
 * @param exitCode   process exit code; {@code -1} indicates the process was
 *                   killed before terminating (typically by wall-time timeout)
 * @param stdout     captured standard output
 * @param stderr     captured standard error
 * @param elapsed    wall-clock duration of the execution
 * @param timedOut   {@code true} when the execution hit the configured
 *                   wall-time limit
 */
public record SandboxExec(
        int exitCode,
        String stdout,
        String stderr,
        Duration elapsed,
        boolean timedOut) {

    public SandboxExec {
        if (stdout == null) {
            stdout = "";
        }
        if (stderr == null) {
            stderr = "";
        }
        if (elapsed == null) {
            elapsed = Duration.ZERO;
        }
    }

    /** Convenience: did the command exit normally with code 0? */
    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
