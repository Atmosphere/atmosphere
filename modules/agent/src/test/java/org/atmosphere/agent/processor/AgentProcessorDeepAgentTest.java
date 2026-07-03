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
import org.atmosphere.ai.preset.DeepAgentPreset;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins that {@code @Agent(deepAgent = true)} forces the deep-agent preset on for
 * a single agent even when the global
 * {@code org.atmosphere.ai.deep-agent.enabled} switch is off, while a plain
 * {@code @Agent} does not — the attribute added in {@link Agent#deepAgent()} and
 * consumed by {@code AgentProcessor}. The decision drives the long-term-memory
 * attach, so the test asserts the observable outcome (a
 * {@link LongTermMemoryInterceptor} is / is not appended) through the real
 * {@link LongTermMemories#withPresetLongTermMemory} helper rather than a no-op.
 */
public class AgentProcessorDeepAgentTest {

    @Agent(name = "x", deepAgent = true)
    static class DeepAgentClass {
    }

    @Agent(name = "y")
    static class PlainAgentClass {
    }

    /**
     * A framework whose deep-agent master switch is off: a mocked config with
     * default init-params ({@code enabled=false}) and a real property bag so the
     * one-shot {@link DeepAgentPreset#install} completes.
     */
    private AtmosphereFramework frameworkWithPresetOff() {
        var framework = mock(AtmosphereFramework.class);
        var cfg = mock(AtmosphereConfig.class);
        Map<String, Object> props = new ConcurrentHashMap<>();
        when(cfg.properties()).thenReturn(props);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
        return framework;
    }

    /**
     * The exact decision {@code AgentProcessor} makes: the {@code deepAgent}
     * attribute forces the preset even when {@code preset.enabledFor(path)} is
     * false because the global switch is off.
     */
    private static boolean presetOn(Agent annotation, DeepAgentPreset preset, String path) {
        return annotation.deepAgent() || preset.enabledFor(path);
    }

    @Test
    public void deepAgentAttributeForcesPresetWithGlobalSwitchOff() {
        var framework = frameworkWithPresetOff();
        var preset = DeepAgentPreset.install(framework);
        assertFalse(preset.enabled(), "global deep-agent switch must be off for this test");

        var annotation = DeepAgentClass.class.getAnnotation(Agent.class);
        var path = "/atmosphere/agent/x";
        var presetOn = presetOn(annotation, preset, path);
        assertTrue(presetOn, "deepAgent=true must force the preset on with the global switch off");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, presetOn, path, framework);
        assertTrue(interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor),
                "deepAgent=true must attach the long-term-memory interceptor");
    }

    @Test
    public void plainAgentDoesNotForcePresetWithGlobalSwitchOff() {
        var framework = frameworkWithPresetOff();
        var preset = DeepAgentPreset.install(framework);

        var annotation = PlainAgentClass.class.getAnnotation(Agent.class);
        var path = "/atmosphere/agent/y";
        var presetOn = presetOn(annotation, preset, path);
        assertFalse(presetOn, "a plain @Agent must not force the preset when the switch is off");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, presetOn, path, framework);
        assertFalse(interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor),
                "a plain @Agent must not attach the long-term-memory interceptor");
    }
}
