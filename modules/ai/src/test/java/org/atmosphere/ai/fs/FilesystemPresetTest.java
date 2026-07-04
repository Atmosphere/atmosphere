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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.plan.PlanningPresetTest;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolKind;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@link FilesystemPreset} attach engine the three processors'
 * {@code registerPresetFilesystem} seams share: feature-off inertness, the
 * built-in six-tool floor under AUTO with a non-capable runtime, native-wins
 * under AUTO with a capable runtime (no duplicate tools), the BUILTIN /
 * NATIVE mode overrides, the partial-collision state honesty, and the
 * runtime-truth state transitions.
 */
public class FilesystemPresetTest {

    @TempDir
    Path root;

    private HarnessPreset preset;
    private DefaultToolRegistry registry;
    private Map<Class<?>, Object> injectables;

    static AgentRuntime fsCapable() {
        return PlanningPresetTest.runtime("native-fs",
                Set.of(AiCapability.TEXT_STREAMING, AiCapability.VIRTUAL_FILESYSTEM));
    }

    static AgentRuntime plain() {
        return PlanningPresetTest.runtime("plain", Set.of(AiCapability.TEXT_STREAMING));
    }

    @BeforeEach
    public void setUp() {
        var framework = mock(AtmosphereFramework.class);
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.properties()).thenReturn(new ConcurrentHashMap<>());
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
        preset = HarnessPreset.install(framework);
        registry = new DefaultToolRegistry();
        injectables = new LinkedHashMap<>();
    }

    @AfterEach
    public void clearKnob() {
        System.clearProperty(AiConfig.FILESYSTEM_PROPERTY);
    }

    private void assertAllSixRegistered() {
        for (var name : new String[]{
                FileSystemTools.LS, FileSystemTools.READ_FILE, FileSystemTools.WRITE_FILE,
                FileSystemTools.EDIT_FILE, FileSystemTools.GLOB, FileSystemTools.GREP}) {
            assertTrue(registry.getTool(name).isPresent(), name + " must be registered");
        }
    }

    @Test
    public void featureOffAttachesNothing() {
        FilesystemPreset.register(registry, preset, false, plain(), injectables, root, "x");

        assertTrue(registry.allTools().isEmpty());
        assertTrue(injectables.isEmpty());
        assertEquals("INACTIVE(no-endpoint)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }

    @Test
    public void autoWithPlainRuntimeRegistersAllSixTools() {
        FilesystemPreset.register(registry, preset, true, plain(), injectables, root, "x");

        assertAllSixRegistered();
        assertInstanceOf(AgentFileSystemProvider.class,
                injectables.get(AgentFileSystemProvider.class),
                "the provider must enter the injectables for conversation scoping");
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
        var provider = (AgentFileSystemProvider) injectables.get(AgentFileSystemProvider.class);
        assertEquals(root, provider.agentRoot());
        assertEquals(AgentFileSystem.Limits.defaults(), provider.limits());
    }

    @Test
    public void autoWithNativeRuntimeSkipsTheBuiltinFloor() {
        FilesystemPreset.register(registry, preset, true, fsCapable(), injectables, root, "x");

        assertTrue(registry.allTools().isEmpty(),
                "native wins in AUTO — no duplicate file tools");
        assertInstanceOf(AgentFileSystemProvider.class,
                injectables.get(AgentFileSystemProvider.class),
                "the provider still enters the injectables for the native bridge");
        assertEquals("ACTIVE(native:native-fs)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }

    @Test
    public void builtinModeBeatsANativeCapableRuntime() {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "builtin");

        FilesystemPreset.register(registry, preset, true, fsCapable(), injectables, root, "x");

        assertAllSixRegistered();
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }

    @Test
    public void nativeModeWithPlainRuntimeAttachesNoSurface() {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "native");

        FilesystemPreset.register(registry, preset, true, plain(), injectables, root, "x");

        assertTrue(registry.allTools().isEmpty(),
                "NATIVE mode must never fall back to the builtin floor");
        assertEquals("INACTIVE(native-unavailable)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }

    @Test
    public void userToolCollisionRegistersTheRestAndReportsPartial() {
        registry.register(ToolDefinition.builder(FileSystemTools.GREP, "user's own grep")
                .executor(args -> "user")
                .kind(ToolKind.READ)
                .build());

        FilesystemPreset.register(registry, preset, true, plain(), injectables, root, "x");

        assertEquals("user's own grep",
                registry.getTool(FileSystemTools.GREP).orElseThrow().description(),
                "the user's tool must win the name");
        assertTrue(registry.getTool(FileSystemTools.LS).isPresent());
        assertFalse("ACTIVE(builtin)".equals(
                        preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM)),
                "a partial attach must not report a full builtin floor (Invariant #5)");
        assertEquals("ACTIVE(builtin,partial)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }
}
