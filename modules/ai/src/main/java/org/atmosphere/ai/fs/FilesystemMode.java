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
package org.atmosphere.ai.fs;

import java.util.Locale;

/**
 * Tri-state control of the harness FILESYSTEM primitive's surface — whether
 * the built-in {@code ls}/{@code read_file}/{@code write_file}/
 * {@code edit_file}/{@code glob}/{@code grep} tool floor or a runtime's
 * native file surface (see
 * {@link org.atmosphere.ai.AiCapability#VIRTUAL_FILESYSTEM}) exposes the
 * {@link AgentFileSystem} store to the model.
 *
 * <p>Mirrors the {@link org.atmosphere.ai.NativeStructuredOutputMode} tri-state
 * convention so operators get one consistent knob shape across the AI module:</p>
 * <ul>
 *   <li>{@link #AUTO} (default) — native wins when the resolved runtime
 *       genuinely advertises {@code AiCapability.VIRTUAL_FILESYSTEM};
 *       otherwise the built-in tool floor registers. Never both — the
 *       built-in tools are not registered when a native bridge is active
 *       (no duplicate tools).</li>
 *   <li>{@link #BUILTIN} — always register the built-in floor, even when the
 *       runtime has native machinery.</li>
 *   <li>{@link #NATIVE} — never register the built-in floor; the file surface
 *       exists only when the resolved runtime advertises the capability. A
 *       runtime without it leaves the primitive inactive (loudly logged).</li>
 * </ul>
 *
 * <p>Resolved once per endpoint registration from the
 * {@code atmosphere.ai.filesystem} system property (falling back to the
 * {@code LLM_FILESYSTEM} environment variable) via
 * {@link org.atmosphere.ai.AiConfig#resolveFilesystemMode()}. Parsing is
 * lenient — an unrecognized value collapses to {@link #AUTO} rather than
 * throwing (Correctness Invariant #4: fail safe at the boundary).</p>
 */
public enum FilesystemMode {

    /** Native file surface when the runtime advertises it; built-in floor otherwise. */
    AUTO,

    /** Always the built-in file-tool floor. */
    BUILTIN,

    /** Only the runtime's native file surface; no built-in floor. */
    NATIVE;

    /**
     * Lenient parse of a tri-state value. {@code null}, blank, or unrecognized
     * input resolves to {@link #AUTO}; matching is case-insensitive and trims
     * surrounding whitespace.
     *
     * @param raw the configured value (may be {@code null})
     * @return the resolved mode, never {@code null}
     */
    public static FilesystemMode parse(String raw) {
        if (raw == null) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "builtin", "built-in" -> BUILTIN;
            case "native" -> NATIVE;
            default -> AUTO;
        };
    }
}
