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

import org.atmosphere.ai.code.SandboxCommand.Language;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the REAL container-execution substrate behind {@code code_exec} —
 * the code-as-action mechanism the browser-agent sample is built on. The other
 * sandbox tests cover command-building and artifact parsing with a fake engine;
 * this one actually starts a container, routes model-style code through it, and
 * reads back stdout/exit code, so the "model writes code, the sandbox runs it"
 * promise is verified end to end and not just at the boundary.
 *
 * <p>Docker-gated: {@link CodeSandboxFactory#isAvailable()} confirms a running
 * container engine, and the test {@code assumeTrue}-skips when none is present
 * (so it runs on CI runners with Docker and is a clean no-op elsewhere). The
 * image is a small Debian-based node image — bash + GNU coreutils for the
 * artifact collector, and {@code node} for the JavaScript path the browser
 * agent actually drives.</p>
 */
class CodeExecSandboxIntegrationTest {

    private static final String IMAGE = "node:20-slim";

    private static CodeSandboxConfig enabledConfig() {
        return new CodeSandboxConfig(
                true, "auto", IMAGE, "none", "512m", 1.0, 128,
                Duration.ofSeconds(60), Duration.ofSeconds(120), 1_000_000, "");
    }

    @Test
    void javascriptRunsInAContainerAndReturnsStdout() throws Exception {
        CodeSandboxFactory factory = CodeSandboxFactory.fromConfig(enabledConfig());
        assumeTrue(factory.isAvailable(),
                "no container engine (docker/podman) available — skipping real code_exec execution");

        try (CodeSandbox sandbox = factory.create("code-exec-it-js")) {
            SandboxResult result = sandbox.exec(new SandboxCommand(
                    Language.JAVASCRIPT, "console.log('SANDBOX_OK ' + (6 * 7))", Duration.ofSeconds(45)));

            assertEquals(0, result.exitCode(),
                    () -> "clean JS should exit 0; stderr=" + result.stderr());
            assertTrue(result.stdout().contains("SANDBOX_OK 42"),
                    () -> "sandbox must return the script's stdout; got: " + result.stdout());
        }
    }

    @Test
    void sandboxIsStatefulAcrossExecsWithinASession() throws Exception {
        CodeSandboxFactory factory = CodeSandboxFactory.fromConfig(enabledConfig());
        assumeTrue(factory.isAvailable(),
                "no container engine (docker/podman) available — skipping real code_exec execution");

        try (CodeSandbox sandbox = factory.create("code-exec-it-state")) {
            // First round writes a file to the working directory...
            SandboxResult write = sandbox.exec(new SandboxCommand(
                    Language.BASH, "echo persisted > state.txt && echo wrote", Duration.ofSeconds(30)));
            assertEquals(0, write.exitCode(), () -> "write round failed: " + write.stderr());

            // ...the next round in the same session must still see it (the
            // code-as-action loop builds up state across calls).
            SandboxResult read = sandbox.exec(new SandboxCommand(
                    Language.BASH, "cat state.txt", Duration.ofSeconds(30)));
            assertEquals(0, read.exitCode(), () -> "read round failed: " + read.stderr());
            assertTrue(read.stdout().contains("persisted"),
                    () -> "working directory must persist across execs; got: " + read.stdout());
        }
    }
}
