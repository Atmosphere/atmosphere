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

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * The single attach engine for the harness FILESYSTEM primitive, shared by
 * the {@code @AiEndpoint} / {@code @Agent} / {@code @Coordinator} processors'
 * package-private {@code registerPresetFilesystem} seams (Correctness
 * Invariant #7, Mode Parity — one decision path, three callers).
 *
 * <p>When the feature resolves for a path, the
 * {@link AgentFileSystemProvider} always enters the injectables (the
 * dispatch seam scopes it per conversation, and native bridges expose the
 * same store through their framework's tool surface), and the surface is
 * picked by {@link FilesystemMode} against the resolved runtime's
 * <em>declared</em> capabilities —
 * {@link AiCapability#VIRTUAL_FILESYSTEM} is itself a runtime-truth
 * contract, so the registration-time check reflects genuine native wiring,
 * not classpath guessing. Native wins in AUTO; the built-in six-tool floor
 * registers otherwise. Never both (no duplicate file tools). Runtime-state
 * flips only on a genuine attach (Invariant #5).</p>
 */
public final class FilesystemPreset {

    private static final Logger logger = LoggerFactory.getLogger(FilesystemPreset.class);

    private FilesystemPreset() {
    }

    /**
     * Attach the FILESYSTEM primitive for one endpoint when the feature is on.
     *
     * @param toolRegistry the endpoint's tool registry
     * @param preset       the installed harness preset (runtime-state sink)
     * @param featureOn    whether {@code Harness.FILESYSTEM} resolved for the path
     * @param runtime      the resolved {@link AgentRuntime} the endpoint will
     *                     dispatch through (may be {@code null} on degraded paths)
     * @param injectables  the endpoint's injectables map — receives the
     *                     {@link AgentFileSystemProvider}
     * @param agentRoot    the owner's workspace root (files persist under
     *                     {@code {agentRoot}/files/{conversationId}/})
     * @param ownerName    the agent / endpoint name (logs)
     */
    public static void register(ToolRegistry toolRegistry, HarnessPreset preset,
                                boolean featureOn, AgentRuntime runtime,
                                Map<Class<?>, Object> injectables,
                                Path agentRoot, String ownerName) {
        if (!featureOn) {
            return;
        }
        var provider = new AgentFileSystemProvider(agentRoot, AgentFileSystem.Limits.defaults());
        injectables.put(AgentFileSystemProvider.class, provider);
        // Publish the same instance for control-plane resolution (the admin
        // REST files endpoints, the console Workspace tab) — never a twin.
        preset.registerFileSystemProvider(ownerName, provider);

        var mode = AiConfig.resolveFilesystemMode();
        var nativeCapable = runtime != null
                && runtime.capabilities().contains(AiCapability.VIRTUAL_FILESYSTEM);
        switch (mode) {
            case NATIVE -> {
                if (nativeCapable) {
                    activateNative(preset, runtime, ownerName);
                } else {
                    preset.updateRuntimeState(HarnessPreset.PRIMITIVE_FILESYSTEM,
                            "INACTIVE(native-unavailable)");
                    logger.warn("Filesystem mode NATIVE but runtime {} does not advertise "
                                    + "VIRTUAL_FILESYSTEM — no file surface for '{}'",
                            runtime != null ? runtime.name() : "<none>", ownerName);
                }
            }
            case AUTO -> {
                if (nativeCapable) {
                    activateNative(preset, runtime, ownerName);
                } else {
                    registerBuiltin(toolRegistry, preset, ownerName);
                }
            }
            case BUILTIN -> registerBuiltin(toolRegistry, preset, ownerName);
        }
    }

    private static void activateNative(HarnessPreset preset, AgentRuntime runtime,
                                       String ownerName) {
        // Native wins: the runtime's own file surface exposes the store — the
        // built-in floor is NOT registered (no duplicate file tools).
        preset.updateRuntimeState(HarnessPreset.PRIMITIVE_FILESYSTEM,
                "ACTIVE(native:" + runtime.name() + ")");
        logger.info("Harness filesystem delegated to native runtime '{}' for '{}'",
                runtime.name(), ownerName);
    }

    private static void registerBuiltin(ToolRegistry toolRegistry, HarnessPreset preset,
                                        String ownerName) {
        var tools = FileSystemTools.all();
        var registered = 0;
        for (var tool : tools) {
            if (toolRegistry.getTool(tool.name()).isPresent()) {
                logger.warn("Harness file tool '{}' skipped for '{}': already registered "
                        + "(user tool wins)", tool.name(), ownerName);
                continue;
            }
            toolRegistry.register(tool);
            registered++;
        }
        if (registered == 0) {
            // Every tool name is user-claimed — a file surface exists, just
            // not the framework's. Report it (Invariant #5), matching the
            // delegate_task / write_todos user-tool convention.
            preset.updateRuntimeState(HarnessPreset.PRIMITIVE_FILESYSTEM, "ACTIVE(user-tool)");
            return;
        }
        preset.updateRuntimeState(HarnessPreset.PRIMITIVE_FILESYSTEM,
                registered == tools.size() ? "ACTIVE(builtin)" : "ACTIVE(builtin,partial)");
        logger.info("Harness registered {} file tool(s) for '{}' (workspace root: {})",
                registered, ownerName, preset.fileSystemProvider(ownerName)
                        .map(p -> p.agentRoot().toString()).orElse("?"));
    }
}
