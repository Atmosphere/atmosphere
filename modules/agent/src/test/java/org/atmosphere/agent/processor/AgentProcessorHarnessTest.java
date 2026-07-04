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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.memory.LongTermMemories;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.preset.Harness;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code @Agent} side of {@link Agent#harness()}: the
 * batteries-included {@code {ALL}} default, the {@code harness = {}}
 * opt-down, a granular single-feature pick, and the
 * {@code org.atmosphere.ai.harness.enabled=false} kill switch — resolved
 * through {@link HarnessPreset#featuresFor} exactly as {@code AgentProcessor}
 * does (annotationIsAgentDefault = true). The MEMORY feature drives the
 * long-term-memory attach, so the tests assert the observable outcome (a
 * {@link LongTermMemoryInterceptor} is / is not appended) through the real
 * {@link LongTermMemories#withPresetLongTermMemory} helper rather than a no-op.
 */
public class AgentProcessorHarnessTest {

    @Agent(name = "x")
    static class DefaultAgentClass {
    }

    @Agent(name = "y", harness = {})
    static class BareAgentClass {
    }

    @Agent(name = "z", harness = {Harness.MEMORY})
    static class MemoryOnlyAgentClass {
    }

    /**
     * A framework with the given raw {@code org.atmosphere.ai.harness.enabled}
     * init-param ({@code null} = unset) and a real property bag so the
     * one-shot {@link HarnessPreset#install} completes.
     */
    private AtmosphereFramework framework(String enabledInitParam) {
        var framework = mock(AtmosphereFramework.class);
        var cfg = mock(AtmosphereConfig.class);
        Map<String, Object> props = new ConcurrentHashMap<>();
        when(cfg.properties()).thenReturn(props);
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn(enabledInitParam);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
        return framework;
    }

    /** The exact resolution {@code AgentProcessor} performs for an agent path. */
    private static Set<Harness> featuresOf(Class<?> agentClass, HarnessPreset preset,
                                           String path) {
        var annotation = agentClass.getAnnotation(Agent.class);
        return preset.featuresFor(path, annotation.harness(), true);
    }

    @Test
    public void defaultAgentGetsTheFullHarness() {
        var framework = framework(null);
        var preset = HarnessPreset.install(framework);
        assertFalse(preset.enabled(), "the app-wide switch stays unset in this test");

        var path = "/atmosphere/agent/x";
        var features = featuresOf(DefaultAgentClass.class, preset, path);
        assertEquals(Set.of(Harness.MEMORY, Harness.CACHE, Harness.DELEGATION,
                        Harness.PLANNING, Harness.FILESYSTEM), features,
                "the {ALL} default must resolve batteries-included with the switch unset");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, features.contains(Harness.MEMORY), path, framework);
        assertTrue(interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor),
                "the default harness must attach the long-term-memory interceptor");
        assertTrue(preset.runtimeState().get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY)
                        .startsWith("ACTIVE("),
                "the attach must publish the long-term-memory primitive as ACTIVE");
    }

    @Test
    public void emptyHarnessOptsDownToABareLoop() {
        var framework = framework(null);
        var preset = HarnessPreset.install(framework);

        var path = "/atmosphere/agent/y";
        var features = featuresOf(BareAgentClass.class, preset, path);
        assertTrue(features.isEmpty(), "harness = {} must resolve to no features");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, features.contains(Harness.MEMORY), path, framework);
        assertFalse(interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor),
                "a bare agent must not attach the long-term-memory interceptor");
        assertEquals("INACTIVE(disabled)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY),
                "an unresolved primitive must stay INACTIVE(disabled)");
    }

    @Test
    public void memoryOnlyHarnessPicksOneFeature() {
        var framework = framework(null);
        var preset = HarnessPreset.install(framework);

        var path = "/atmosphere/agent/z";
        var features = featuresOf(MemoryOnlyAgentClass.class, preset, path);
        assertEquals(Set.of(Harness.MEMORY), features);
        assertFalse(features.contains(Harness.CACHE), "CACHE must stay off");
        assertFalse(features.contains(Harness.DELEGATION), "DELEGATION must stay off");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, features.contains(Harness.MEMORY), path, framework);
        assertTrue(interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor),
                "harness = {MEMORY} must attach the long-term-memory interceptor");
        var state = preset.runtimeState();
        assertTrue(state.get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY).startsWith("ACTIVE("),
                "the memory primitive must report ACTIVE");
        assertEquals("INACTIVE(disabled)", state.get(HarnessPreset.PRIMITIVE_DELEGATION),
                "the delegation primitive must stay INACTIVE(disabled)");
        assertEquals("none", state.get(HarnessPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT),
                "the prompt-cache primitive must stay unseeded");
    }

    @Test
    public void killSwitchBeatsTheDefaultOnAgent() {
        var framework = framework("false");
        var preset = HarnessPreset.install(framework);
        assertTrue(preset.killSwitch(), "enabled=false must read as the kill switch");

        var path = "/atmosphere/agent/x";
        var features = featuresOf(DefaultAgentClass.class, preset, path);
        assertTrue(features.isEmpty(), "the kill switch must beat the {ALL} default");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, features.contains(Harness.MEMORY), path, framework);
        assertFalse(interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor),
                "the kill switch must suppress the long-term-memory attach");
    }

    // ---- planning / filesystem seams (registerPresetPlanning / -Filesystem) ----

    @Test
    public void defaultAgentAttachesThePlanningAndFilesystemFloors() {
        var framework = framework(null);
        var preset = HarnessPreset.install(framework);
        var features = featuresOf(DefaultAgentClass.class, preset, "/atmosphere/agent/x");
        assertTrue(features.contains(Harness.PLANNING),
                "the {ALL} default must include PLANNING");
        assertTrue(features.contains(Harness.FILESYSTEM),
                "the {ALL} default must include FILESYSTEM");

        var processor = new AgentProcessor();
        var registry = new org.atmosphere.ai.tool.DefaultToolRegistry();
        var injectables = new java.util.LinkedHashMap<Class<?>, Object>();
        processor.registerPresetPlanning(registry, preset,
                features.contains(Harness.PLANNING), null, injectables, "x");
        processor.registerPresetFilesystem(registry, preset,
                features.contains(Harness.FILESYSTEM), null, injectables, "x");

        assertTrue(registry.getTool("write_todos").isPresent(),
                "the default harness must register the write_todos floor");
        for (var name : new String[]{"ls", "read_file", "write_file",
                "edit_file", "glob", "grep"}) {
            assertTrue(registry.getTool(name).isPresent(),
                    "the default harness must register the '" + name + "' file tool");
        }
        assertTrue(injectables.get(org.atmosphere.ai.plan.AgentPlanStore.class)
                        instanceof org.atmosphere.ai.plan.FileSystemAgentPlanStore,
                "the plan store must be injectable for @Prompt / @AiTool methods");
        assertTrue(injectables.containsKey(org.atmosphere.ai.fs.AgentFileSystemProvider.class),
                "the filesystem provider must be injectable for conversation scoping");
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
        assertEquals("ACTIVE(builtin)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }

    @Test
    public void bareAgentAttachesNoPlanningOrFilesystem() {
        var framework = framework(null);
        var preset = HarnessPreset.install(framework);
        var features = featuresOf(BareAgentClass.class, preset, "/atmosphere/agent/y");

        var processor = new AgentProcessor();
        var registry = new org.atmosphere.ai.tool.DefaultToolRegistry();
        var injectables = new java.util.LinkedHashMap<Class<?>, Object>();
        processor.registerPresetPlanning(registry, preset,
                features.contains(Harness.PLANNING), null, injectables, "y");
        processor.registerPresetFilesystem(registry, preset,
                features.contains(Harness.FILESYSTEM), null, injectables, "y");

        assertTrue(registry.allTools().isEmpty(),
                "harness = {} must register no planning / filesystem tools");
        assertTrue(injectables.isEmpty(),
                "harness = {} must not wire the plan store or filesystem provider");
        assertEquals("INACTIVE(disabled)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_PLANNING));
        assertEquals("INACTIVE(disabled)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_FILESYSTEM));
    }
}
