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
 * Creates a {@link CodeSandbox} for a streaming session. Implementations back
 * the code-as-action tool with a concrete substrate (an ephemeral container,
 * for the production implementation).
 *
 * <p>{@link #isAvailable()} reflects <em>confirmed runtime state</em>, not
 * configuration intent: it returns {@code true} only when code execution is both
 * enabled in config <em>and</em> the substrate is actually usable right now (the
 * container engine responds). This is Correctness Invariant #5 (Runtime Truth) —
 * the code-exec capability is advertised only when it can really run, never on
 * the strength of a config flag alone.</p>
 */
public interface CodeSandboxFactory {

    /**
     * Whether the code-as-action substrate is enabled <em>and</em> confirmed
     * usable at runtime. Callers MUST consult this before offering the
     * {@code code_exec} tool to a model.
     *
     * @return {@code true} only when a sandbox can actually be created now
     */
    boolean isAvailable();

    /**
     * Provision a fresh sandbox for the given session. The caller owns the
     * returned sandbox and MUST {@link CodeSandbox#close() close} it when the
     * session reaches a terminal state.
     *
     * @param sessionId the owning session's id, used to label the sandbox
     * @return a ready sandbox
     * @throws SandboxException if provisioning fails or the substrate is unavailable
     */
    CodeSandbox create(String sessionId) throws SandboxException;

    /**
     * Resolve the factory for a configuration. Returns {@link #disabled()} when
     * code execution is off (default deny), otherwise a container-backed factory
     * whose {@link #isAvailable()} still gates on a confirmed runtime engine.
     * This is the single wiring entry point the pipeline uses.
     *
     * @param config the resolved sandbox configuration
     * @return a factory honoring the configuration's gating
     */
    static CodeSandboxFactory fromConfig(CodeSandboxConfig config) {
        if (config == null || !config.enabled()) {
            return disabled();
        }
        return new ContainerCodeSandboxFactory(config);
    }

    /**
     * The default-deny factory: never available, always refuses to create a
     * sandbox. This is the baseline when code execution is not configured, so
     * the tool surface is simply absent rather than half-wired.
     */
    static CodeSandboxFactory disabled() {
        return new CodeSandboxFactory() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public CodeSandbox create(String sessionId) throws SandboxException {
                throw new SandboxException(
                        "Code execution is disabled. Set " + CodeSandboxConfig.ENABLED
                                + "=true and provide " + CodeSandboxConfig.IMAGE
                                + " to enable the code-as-action sandbox.");
            }
        };
    }
}
