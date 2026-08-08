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
package org.atmosphere.ai.state.seal;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line reseal step for the opt-in agent state seal: re-signs every
 * state file under a workspace root as it currently is, so a deliberate
 * operator hand-edit stops failing verification. This is the remediation
 * named by every {@link AgentStateSealException} the sealer raises.
 *
 * <pre>
 *   java -cp … org.atmosphere.ai.state.seal.AgentStateReseal &lt;workspaceRoot&gt; [--key-file &lt;path&gt;]
 * </pre>
 *
 * <p>Uses the same key resolution as the running control: the
 * {@code --key-file} argument if given, else the
 * {@code atmosphere.ai.state.seal.key-file} property /
 * {@code ATMOSPHERE_AI_STATE_SEAL_KEY_FILE} environment variable, else the
 * managed key at {@code {workspaceRoot}.seal/state-seal.key} (generated if
 * absent). Resealing is an explicit trust decision — run it only for edits
 * you made or reviewed.</p>
 *
 * <p>The equivalent without a separate JVM is a one-shot restart with
 * {@code -Datmosphere.ai.state.seal.reseal=true}.</p>
 */
public final class AgentStateReseal {

    private AgentStateReseal() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Testable body of {@link #main}: parses arguments, reseals, reports on
     * stderr. Returns the process exit code — {@code 0} on success,
     * {@code 1} when the reseal fails, {@code 2} on usage errors.
     */
    static int run(String[] args) {
        Path root = null;
        Path keyFile = null;
        for (var i = 0; i < args.length; i++) {
            if ("--key-file".equals(args[i])) {
                if (i + 1 >= args.length) {
                    return usage("--key-file requires a path argument");
                }
                keyFile = Path.of(args[++i]);
            } else if (root == null) {
                root = Path.of(args[i]);
            } else {
                return usage("unexpected argument: " + args[i]);
            }
        }
        if (root == null) {
            return usage("workspaceRoot is required");
        }
        if (!Files.isDirectory(root)) {
            System.err.println("workspace root is not a directory: " + root);
            return 1;
        }
        try {
            var envKeyFile = System.getProperty(AgentStateSealer.KEY_FILE_PROPERTY);
            if (envKeyFile == null) {
                envKeyFile = System.getenv(AgentStateSealer.KEY_FILE_ENV);
            }
            var sealer = AgentStateSealer.forWorkspace(root,
                    keyFile != null ? keyFile
                            : envKeyFile != null ? Path.of(envKeyFile) : null,
                    false);
            var sealed = sealer.resealAll();
            System.err.printf("resealed %d state file(s) under %s with key %s%n",
                    sealed, sealer.workspaceRoot(), sealer.keyId());
            return 0;
        } catch (AgentStateSealException e) {
            System.err.println("reseal failed: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(String problem) {
        System.err.println(problem);
        System.err.println(
                "usage: AgentStateReseal <workspaceRoot> [--key-file <path>]");
        return 2;
    }
}
