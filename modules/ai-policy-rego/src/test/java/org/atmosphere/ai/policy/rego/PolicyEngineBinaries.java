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
package org.atmosphere.ai.policy.rego;

import java.util.concurrent.TimeUnit;

/**
 * Test-only helper that resolves a policy-engine CLI binary and probes that
 * it actually runs, so integration tests can {@code assumeTrue} on its
 * presence and skip cleanly on runners that lack the engine.
 */
final class PolicyEngineBinaries {

    private PolicyEngineBinaries() {
    }

    /**
     * Resolve a binary from (in order) a system property, an environment
     * variable, or a default name on {@code PATH}, then verify it executes
     * by running it with {@code probeArgs}. Returns the usable binary path,
     * or {@code null} when the binary cannot be found or does not run.
     *
     * @param sysProp     system property that may carry an explicit path
     * @param envVar      environment variable that may carry an explicit path
     * @param defaultName binary name resolved against {@code PATH} as a fallback
     * @param probeArgs   arguments that make the binary exit 0 (e.g. {@code version})
     */
    static String resolve(String sysProp, String envVar, String defaultName, String... probeArgs) {
        var candidate = System.getProperty(sysProp);
        if (candidate == null || candidate.isBlank()) {
            candidate = System.getenv(envVar);
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = defaultName;
        }
        return runs(candidate, probeArgs) ? candidate : null;
    }

    private static boolean runs(String binary, String... probeArgs) {
        var command = new java.util.ArrayList<String>();
        command.add(binary);
        command.addAll(java.util.List.of(probeArgs));
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (java.io.IOException e) {
            // Binary not found on PATH / not executable.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
