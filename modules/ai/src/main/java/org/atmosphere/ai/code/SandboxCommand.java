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

/**
 * A single unit of code to execute in a {@link CodeSandbox}: the interpreter to
 * run it under, the source text, and the per-command wall-clock timeout.
 *
 * @param language the interpreter the sandbox routes this code to
 * @param code     the source text to execute; must not be {@code null}/blank
 * @param timeout  per-command wall-clock budget; {@code null} means the sandbox
 *                 applies {@link CodeSandboxConfig#execTimeout()}
 */
public record SandboxCommand(Language language, String code, Duration timeout) {

    /** Interpreters a sandbox may expose. The substrate maps each to a runner. */
    public enum Language {
        /** POSIX shell, run via {@code bash -c}. */
        BASH,
        /** JavaScript, run via {@code node}. The primary driver for browser automation. */
        JAVASCRIPT,
        /** Python, run via {@code python3}. */
        PYTHON
    }

    public SandboxCommand {
        if (language == null) {
            throw new IllegalArgumentException("language must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        // timeout may be null — the sandbox falls back to its configured default.
    }

    /** Convenience: a command with no explicit timeout (sandbox default applies). */
    public static SandboxCommand of(Language language, String code) {
        return new SandboxCommand(language, code, null);
    }
}
