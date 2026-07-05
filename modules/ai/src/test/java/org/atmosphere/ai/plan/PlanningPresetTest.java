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
package org.atmosphere.ai.plan;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
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
 * Pins the {@link PlanningPreset} attach engine the three processors'
 * {@code registerPresetPlanning} seams share: feature-off inertness, the
 * built-in floor under AUTO with a non-capable runtime, native-wins under
 * AUTO with a capable runtime (no duplicate tools), the BUILTIN / NATIVE
 * mode overrides, the user-tool collision rule, and the runtime-truth state
 * transitions.
 */
public class PlanningPresetTest {

    @TempDir
    Path root;

    private HarnessPreset preset;
    private DefaultToolRegistry registry;
    private Map<Class<?>, Object> injectables;

    /** Minimal runtime stub with a declared capability set (shared with FilesystemPresetTest). */
    public static AgentRuntime runtime(String name, Set<AiCapability> capabilities) {
        return new AgentRuntime() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public Set<AiCapability> capabilities() {
                return capabilities;
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.complete();
            }
        };
    }

    static AgentRuntime planningCapable() {
        return runtime("native-planner",
                Set.of(AiCapability.TEXT_STREAMING, AiCapability.PLANNING));
    }

    static AgentRuntime plain() {
        return runtime("plain", Set.of(AiCapability.TEXT_STREAMING));
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
        System.clearProperty(AiConfig.PLANNING_PROPERTY);
    }

    @Test
    public void featureOffAttachesNothing() {
        PlanningPreset.register(registry, preset, false, plain(), injectables, root, "x");

        assertTrue(registry.allTools().isEmpty());
        assertTrue(injectables.isEmpty(), "the plan store must not leak when the feature is off");
        assertEquals("INACTIVE(no-endpoint)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void autoWithPlainRuntimeRegistersTheBuiltinFloor() {
        PlanningPreset.register(registry, preset, true, plain(), injectables, root, "x");

        assertTrue(registry.getTool(PlanningTools.WRITE_TODOS).isPresent());
        assertInstanceOf(FileSystemAgentPlanStore.class, injectables.get(AgentPlanStore.class),
                "the plan store must enter the injectables");
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void autoWithNativeRuntimeSkipsTheBuiltinFloor() {
        PlanningPreset.register(registry, preset, true, planningCapable(),
                injectables, root, "x");

        assertFalse(registry.getTool(PlanningTools.WRITE_TODOS).isPresent(),
                "native wins in AUTO — no duplicate plan tools");
        assertInstanceOf(FileSystemAgentPlanStore.class, injectables.get(AgentPlanStore.class),
                "the store still enters the injectables for the native bridge");
        assertEquals("ACTIVE(native:native-planner)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void builtinModeBeatsANativeCapableRuntime() {
        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin");

        PlanningPreset.register(registry, preset, true, planningCapable(),
                injectables, root, "x");

        assertTrue(registry.getTool(PlanningTools.WRITE_TODOS).isPresent());
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void nativeModeWithPlainRuntimeAttachesNoSurface() {
        System.setProperty(AiConfig.PLANNING_PROPERTY, "native");

        PlanningPreset.register(registry, preset, true, plain(), injectables, root, "x");

        assertFalse(registry.getTool(PlanningTools.WRITE_TODOS).isPresent(),
                "NATIVE mode must never fall back to the builtin floor");
        assertEquals("INACTIVE(native-unavailable)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void nativeModeWithCapableRuntimeActivatesNative() {
        System.setProperty(AiConfig.PLANNING_PROPERTY, "native");

        PlanningPreset.register(registry, preset, true, planningCapable(),
                injectables, root, "x");

        assertFalse(registry.getTool(PlanningTools.WRITE_TODOS).isPresent());
        assertEquals("ACTIVE(native:native-planner)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void nullRuntimeFallsBackToBuiltinUnderAuto() {
        PlanningPreset.register(registry, preset, true, null, injectables, root, "x");

        assertTrue(registry.getTool(PlanningTools.WRITE_TODOS).isPresent());
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
    }

    @Test
    public void userToolCollisionKeepsTheUserToolAndReportsUserToolState() {
        registry.register(ToolDefinition.builder(PlanningTools.WRITE_TODOS, "user's own")
                .executor(args -> "user")
                .kind(ToolKind.EDIT)
                .build());

        PlanningPreset.register(registry, preset, true, plain(), injectables, root, "x");

        assertEquals("user's own",
                registry.getTool(PlanningTools.WRITE_TODOS).orElseThrow().description(),
                "the user's tool must win the name");
        // A plan surface genuinely exists — the user's own write_todos tool —
        // so the honest runtime state is ACTIVE(user-tool), matching the
        // delegation convention (CoordinatorProcessor.registerPresetDelegation
        // reports the same for a hand-written delegate_task). Leaving it
        // INACTIVE would under-report a surface the model can actually call
        // (Invariant #5 cuts both ways: no over-claim, but no under-claim of a
        // confirmed surface either).
        assertEquals("ACTIVE(user-tool)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING),
                "a user-provided plan tool is a genuine surface — report it");
    }
}
