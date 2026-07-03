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
package org.atmosphere.ai.preset;

import org.atmosphere.ai.llm.PromptCacheDefaults;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DeepAgentPresetTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig cfg;
    private ConcurrentHashMap<String, Object> props;

    @BeforeEach
    public void setUp() {
        framework = mock(AtmosphereFramework.class);
        cfg = mock(AtmosphereConfig.class);
        props = new ConcurrentHashMap<>();
        when(cfg.properties()).thenReturn(props);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
    }

    @Test
    public void disabledByDefault() {
        var preset = DeepAgentPreset.install(framework);

        assertFalse(preset.enabled(), "the preset must be default-off");
        assertFalse(preset.enabledFor("/atmosphere/chat"));
    }

    @Test
    public void enabledViaInitParam() {
        when(cfg.getInitParameter(DeepAgentPreset.ENABLED_KEY, false)).thenReturn(true);

        var preset = DeepAgentPreset.install(framework);

        assertTrue(preset.enabled());
        assertTrue(preset.enabledFor("/atmosphere/chat"));
    }

    @Test
    public void excludePathsAreExactAndTrimmed() {
        when(cfg.getInitParameter(DeepAgentPreset.ENABLED_KEY, false)).thenReturn(true);
        when(cfg.getInitParameter(DeepAgentPreset.EXCLUDE_PATHS_KEY))
                .thenReturn(" /atmosphere/legacy , /atmosphere/raw,, ");

        var preset = DeepAgentPreset.install(framework);

        assertFalse(preset.enabledFor("/atmosphere/legacy"), "excluded path must opt out");
        assertFalse(preset.enabledFor("/atmosphere/raw"));
        assertTrue(preset.enabledFor("/atmosphere/legacy/sub"),
                "exclusion is exact-match, not prefix-match");
        assertTrue(preset.enabledFor("/atmosphere/chat"));
        assertFalse(preset.enabledFor(null), "a null path never matches the preset");
    }

    @Test
    public void installIsOneShotPerFramework() {
        var first = DeepAgentPreset.install(framework);

        // Config change after install must not produce a second instance —
        // the property-bag marker wins (same pattern as MemorySafetyConfig).
        when(cfg.getInitParameter(DeepAgentPreset.ENABLED_KEY, false)).thenReturn(true);
        var second = DeepAgentPreset.install(framework);

        assertSame(first, second, "install must be one-shot per framework");
        assertFalse(second.enabled(), "the first parse is authoritative");
    }

    @Test
    public void nullConfigYieldsDisabledPreset() {
        var bare = mock(AtmosphereFramework.class);
        var preset = DeepAgentPreset.install(bare);

        assertFalse(preset.enabled());
        assertFalse(preset.enabledFor("/anything"));
    }

    @Test
    public void runtimeStatePublishedWhenEnabled() {
        when(cfg.getInitParameter(DeepAgentPreset.ENABLED_KEY, false)).thenReturn(true);

        var preset = DeepAgentPreset.install(framework);

        var published = props.get(DeepAgentPreset.RUNTIME_STATE_PROPERTY);
        assertNotNull(published, "runtime-state map must be published to the property bag");
        assertTrue(published instanceof Map<?, ?>);
        var state = preset.runtimeState();
        assertEquals("ACTIVE", state.get(DeepAgentPreset.PRIMITIVE_CONVERSATION_MEMORY));
        assertEquals("INACTIVE(no-endpoint)", state.get(DeepAgentPreset.PRIMITIVE_LONG_TERM_MEMORY));
        assertEquals("INACTIVE(no-coordinator)", state.get(DeepAgentPreset.PRIMITIVE_DELEGATION));
        assertEquals("sliding-window", state.get(DeepAgentPreset.PRIMITIVE_COMPACTION));
        assertEquals("conservative", state.get(DeepAgentPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT));
        assertEquals("CONVENTION", state.get(DeepAgentPreset.PRIMITIVE_SKILLS));
        assertEquals("CONTAINER-MANAGED", state.get(DeepAgentPreset.PRIMITIVE_DURABLE_RUNS));
    }

    @Test
    public void runtimeStateReportsDisabledPrimitives() {
        var preset = DeepAgentPreset.install(framework);

        var state = preset.runtimeState();
        assertEquals("INACTIVE(disabled)", state.get(DeepAgentPreset.PRIMITIVE_CONVERSATION_MEMORY));
        assertEquals("INACTIVE(disabled)", state.get(DeepAgentPreset.PRIMITIVE_LONG_TERM_MEMORY));
        assertEquals("INACTIVE(disabled)", state.get(DeepAgentPreset.PRIMITIVE_DELEGATION));
        assertEquals("none", state.get(DeepAgentPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT));
    }

    @Test
    public void runtimeStateHonorsIndependentSeamsWhenDisabled() {
        // compaction + prompt-cache.default work with the preset off — the
        // published state must reflect the configured values, not "disabled".
        when(cfg.getInitParameter("org.atmosphere.ai.compaction")).thenReturn("summarizing");
        when(cfg.getInitParameter(PromptCacheDefaults.DEFAULT_KEY)).thenReturn("aggressive");

        var preset = DeepAgentPreset.install(framework);

        var state = preset.runtimeState();
        assertEquals("summarizing", state.get(DeepAgentPreset.PRIMITIVE_COMPACTION));
        assertEquals("aggressive", state.get(DeepAgentPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT));
    }

    @Test
    public void updateRuntimeStateReflectsInPublishedMap() {
        when(cfg.getInitParameter(DeepAgentPreset.ENABLED_KEY, false)).thenReturn(true);
        var preset = DeepAgentPreset.install(framework);

        preset.updateRuntimeState(DeepAgentPreset.PRIMITIVE_LONG_TERM_MEMORY,
                "ACTIVE(org.example.Store)");

        // The published map is the live instance, not a snapshot.
        var published = props.get(DeepAgentPreset.RUNTIME_STATE_PROPERTY);
        assertTrue(published instanceof Map<?, ?> m
                        && "ACTIVE(org.example.Store)".equals(
                                m.get(DeepAgentPreset.PRIMITIVE_LONG_TERM_MEMORY)),
                "updates must be visible through the published map");
    }
}
