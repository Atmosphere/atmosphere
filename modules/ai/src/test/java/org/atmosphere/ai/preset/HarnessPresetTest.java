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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HarnessPresetTest {

    private static final Harness[] BARE = {};
    private static final Harness[] AGENT_DEFAULT = {Harness.ALL};
    private static final Set<Harness> FULL =
            Set.of(Harness.MEMORY, Harness.CACHE, Harness.DELEGATION);

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

    // ---- tri-state app-wide switch ----

    @Test
    public void unsetByDefaultLeavesTheDecisionToAnnotations() {
        var preset = HarnessPreset.install(framework);

        assertFalse(preset.enabled(), "unset must not read as app-wide on");
        assertFalse(preset.killSwitch(), "unset must not read as the kill switch");
        assertTrue(preset.featuresFor("/atmosphere/chat", BARE, false).isEmpty(),
                "a bare @AiEndpoint stays bare under the unset default");
        assertEquals(FULL, preset.featuresFor("/atmosphere/agent/x", AGENT_DEFAULT, true),
                "the @Agent {ALL} default resolves batteries-included under the unset default");
    }

    @Test
    public void appWideTrueGivesBareEndpointsTheFullHarness() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");

        var preset = HarnessPreset.install(framework);

        assertTrue(preset.enabled());
        assertFalse(preset.killSwitch());
        assertEquals(FULL, preset.featuresFor("/atmosphere/chat", BARE, false));
    }

    @Test
    public void killSwitchBeatsEveryAnnotation() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("false");

        var preset = HarnessPreset.install(framework);

        assertTrue(preset.killSwitch());
        assertFalse(preset.enabled());
        assertTrue(preset.featuresFor("/atmosphere/agent/x", AGENT_DEFAULT, true).isEmpty(),
                "the kill switch must beat the @Agent {ALL} default");
        assertTrue(preset.featuresFor("/atmosphere/chat",
                        new Harness[]{Harness.MEMORY}, false).isEmpty(),
                "the kill switch must beat an explicit non-empty annotation");
    }

    // ---- annotation precedence ----

    @Test
    public void nonEmptyAnnotationWinsWithoutTheAppWideFlag() {
        var preset = HarnessPreset.install(framework);

        assertEquals(Set.of(Harness.MEMORY),
                preset.featuresFor("/atmosphere/chat", new Harness[]{Harness.MEMORY}, false),
                "a per-endpoint opt-in must not need the app-wide flag");
    }

    @Test
    public void emptyAgentAnnotationIsAnOptDownEvenWithAppWideTrue() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");

        var preset = HarnessPreset.install(framework);

        assertTrue(preset.featuresFor("/atmosphere/agent/x", BARE, true).isEmpty(),
                "@Agent(harness = {}) is a deliberate opt-down the app-wide flag must not override");
        assertEquals(FULL, preset.featuresFor("/atmosphere/chat", BARE, false),
                "a bare @AiEndpoint gets the full harness under app-wide true");
    }

    // ---- exclude-paths ----

    @Test
    public void excludePathsAreExactAndTrimmed() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
        when(cfg.getInitParameter(HarnessPreset.EXCLUDE_PATHS_KEY))
                .thenReturn(" /atmosphere/legacy , /atmosphere/raw,, ");

        var preset = HarnessPreset.install(framework);

        assertTrue(preset.featuresFor("/atmosphere/legacy", BARE, false).isEmpty(),
                "excluded path must opt out");
        assertTrue(preset.featuresFor("/atmosphere/raw", AGENT_DEFAULT, true).isEmpty(),
                "exclude-paths must beat the annotation's harness()");
        assertEquals(FULL, preset.featuresFor("/atmosphere/legacy/sub", BARE, false),
                "exclusion is exact-match, not prefix-match");
        assertEquals(FULL, preset.featuresFor("/atmosphere/chat", BARE, false));
        assertTrue(preset.featuresFor(null, AGENT_DEFAULT, true).isEmpty(),
                "a null path never matches the harness");
    }

    // ---- install lifecycle ----

    @Test
    public void installIsOneShotPerFramework() {
        var first = HarnessPreset.install(framework);

        // Config change after install must not produce a second instance —
        // the property-bag marker wins (same pattern as MemorySafetyConfig).
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
        var second = HarnessPreset.install(framework);

        assertSame(first, second, "install must be one-shot per framework");
        assertFalse(second.enabled(), "the first parse is authoritative");
    }

    @Test
    public void nullConfigYieldsUnsetPreset() {
        var bare = mock(AtmosphereFramework.class);
        var preset = HarnessPreset.install(bare);

        assertFalse(preset.enabled());
        assertFalse(preset.killSwitch());
        assertTrue(preset.featuresFor("/anything", BARE, false).isEmpty());
    }

    // ---- runtime-state publication ----

    @Test
    public void runtimeStatePublishedWhenEnabled() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");

        var preset = HarnessPreset.install(framework);

        var published = props.get(HarnessPreset.RUNTIME_STATE_PROPERTY);
        assertNotNull(published, "runtime-state map must be published to the property bag");
        assertTrue(published instanceof Map<?, ?>);
        var state = preset.runtimeState();
        assertEquals("ACTIVE", state.get(HarnessPreset.PRIMITIVE_CONVERSATION_MEMORY));
        assertEquals("INACTIVE(no-endpoint)", state.get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY));
        assertEquals("INACTIVE(no-coordinator)", state.get(HarnessPreset.PRIMITIVE_DELEGATION));
        assertEquals("sliding-window", state.get(HarnessPreset.PRIMITIVE_COMPACTION));
        assertEquals("conservative", state.get(HarnessPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT));
        assertEquals("CONVENTION", state.get(HarnessPreset.PRIMITIVE_SKILLS));
        assertEquals("CONTAINER-MANAGED", state.get(HarnessPreset.PRIMITIVE_DURABLE_RUNS));
    }

    @Test
    public void runtimeStateSeedsInactiveUntilPrimitivesAttach() {
        var preset = HarnessPreset.install(framework);

        var state = preset.runtimeState();
        assertEquals("INACTIVE(disabled)", state.get(HarnessPreset.PRIMITIVE_CONVERSATION_MEMORY));
        assertEquals("INACTIVE(disabled)", state.get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY));
        assertEquals("INACTIVE(disabled)", state.get(HarnessPreset.PRIMITIVE_DELEGATION));
        assertEquals("none", state.get(HarnessPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT));
    }

    @Test
    public void runtimeStateHonorsIndependentSeamsWhenUnset() {
        // compaction + prompt-cache.default work without the harness — the
        // published state must reflect the configured values, not "disabled".
        when(cfg.getInitParameter("org.atmosphere.ai.compaction")).thenReturn("summarizing");
        when(cfg.getInitParameter(PromptCacheDefaults.DEFAULT_KEY)).thenReturn("aggressive");

        var preset = HarnessPreset.install(framework);

        var state = preset.runtimeState();
        assertEquals("summarizing", state.get(HarnessPreset.PRIMITIVE_COMPACTION));
        assertEquals("aggressive", state.get(HarnessPreset.PRIMITIVE_PROMPT_CACHE_DEFAULT));
    }

    @Test
    public void updateRuntimeStateReflectsInPublishedMap() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
        var preset = HarnessPreset.install(framework);

        preset.updateRuntimeState(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY,
                "ACTIVE(org.example.Store)");

        // The published map is the live instance, not a snapshot.
        var published = props.get(HarnessPreset.RUNTIME_STATE_PROPERTY);
        assertTrue(published instanceof Map<?, ?> m
                        && "ACTIVE(org.example.Store)".equals(
                                m.get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY)),
                "updates must be visible through the published map");
    }
}
