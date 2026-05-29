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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the container-engine argument lists used by {@link ContainerCodeSandbox}.
 * Extracted as a pure, side-effect-free unit so the security-critical command
 * construction is unit-testable without a live engine.
 *
 * <p><strong>Boundary safety (Correctness Invariant #4).</strong> Every command
 * is assembled as a {@link List} of discrete arguments handed to
 * {@link ProcessBuilder} — never a shell string. Model-generated code is
 * <em>never</em> placed on the command line; it is written to the interpreter's
 * standard input (see {@link #execArgs}). There is therefore no shell parsing,
 * no word-splitting, and no variable expansion of attacker-influenced text.</p>
 */
final class ContainerCommandBuilder {

    /** Fixed workspace mount point inside the container. */
    static final String WORKSPACE = "/workspace";

    private ContainerCommandBuilder() {
    }

    /**
     * {@code <engine> run -d ...hardening... <image> sleep <ttl>} — starts a
     * long-lived but capped container the session execs into. The container is
     * kept alive by an explicit {@code sleep} entrypoint and torn down via
     * {@link #removeArgs}; {@code --rm} ensures the engine also reaps it if the
     * daemon restarts.
     */
    static List<String> runArgs(String engine, String containerName, CodeSandboxConfig config) {
        var args = new ArrayList<String>();
        args.add(engine);
        args.add("run");
        args.add("-d");
        args.add("--rm");
        args.add("--name");
        args.add(containerName);
        // --- isolation / hardening (default deny) ---
        args.add("--network");
        args.add(config.network());
        args.add("--memory");
        args.add(config.memory());
        args.add("--cpus");
        args.add(formatCpus(config.cpus()));
        args.add("--pids-limit");
        args.add(Integer.toString(config.pidsLimit()));
        args.add("--read-only");
        args.add("--cap-drop");
        args.add("ALL");
        args.add("--security-opt");
        args.add("no-new-privileges");
        // Writable scratch that does not survive teardown; bounded so a runaway
        // script cannot fill the host disk (Backpressure, Invariant #3). The
        // rootfs stays read-only — only these tmpfs mounts are writable. /tmp is
        // needed by browsers (Chromium profile/temp) and many tools; HOME points
        // at the workspace so package managers write their cache there.
        args.add("--tmpfs");
        args.add(WORKSPACE + ":rw,exec,size=512m");
        args.add("--tmpfs");
        args.add("/tmp:rw,exec,size=256m");
        args.add("--env");
        args.add("HOME=" + WORKSPACE);
        args.add("--workdir");
        args.add(WORKSPACE);
        // Keep the container alive for the session's lifetime, capped by TTL.
        args.add("--entrypoint");
        args.add("sleep");
        args.add(config.image());
        args.add(Long.toString(config.sandboxTtl().toSeconds()));
        return List.copyOf(args);
    }

    /**
     * {@code <engine> exec -i <name> <interpreter>} — the model's code is piped
     * to the interpreter's stdin by the caller, so it never appears as an
     * argument.
     */
    static List<String> execArgs(String engine, String containerName,
                                 SandboxCommand.Language language) {
        var args = new ArrayList<String>();
        args.add(engine);
        args.add("exec");
        args.add("-i");
        args.add(containerName);
        args.addAll(interpreter(language));
        return List.copyOf(args);
    }

    /** {@code <engine> info --format {{.ServerVersion}}} — runtime availability probe. */
    static List<String> infoArgs(String engine) {
        return List.of(engine, "info", "--format", "{{.ServerVersion}}");
    }

    /** {@code <engine> rm -f <name>} — idempotent teardown. */
    static List<String> removeArgs(String engine, String containerName) {
        return List.of(engine, "rm", "-f", containerName);
    }

    /** Maps a language to the interpreter argv that reads code from stdin. */
    static List<String> interpreter(SandboxCommand.Language language) {
        return switch (language) {
            case BASH -> List.of("bash");
            case JAVASCRIPT -> List.of("node");
            case PYTHON -> List.of("python3");
        };
    }

    private static String formatCpus(double cpus) {
        // Avoid locale-specific decimal separators in the engine argument.
        if (cpus == Math.rint(cpus)) {
            return Long.toString((long) cpus);
        }
        return String.format(Locale.ROOT, "%.2f", cpus);
    }
}
