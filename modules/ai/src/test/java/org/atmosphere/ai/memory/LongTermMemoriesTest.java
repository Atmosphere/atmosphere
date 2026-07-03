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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.preset.Harness;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LongTermMemoriesTest {

    private AtmosphereFramework framework;
    private ConcurrentHashMap<String, Object> props;

    @BeforeEach
    public void setUp() {
        framework = mock(AtmosphereFramework.class);
        var cfg = mock(AtmosphereConfig.class);
        props = new ConcurrentHashMap<>();
        when(cfg.properties()).thenReturn(props);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
        TestLongTermMemoryProviders.reset();
    }

    @AfterEach
    public void tearDown() {
        TestLongTermMemoryProviders.reset();
    }

    @Test
    public void fallsBackToInMemoryWhenNothingConfigured() {
        var store = LongTermMemories.resolve(framework);

        assertInstanceOf(InMemoryLongTermMemory.class, store);
        assertNotSame(TestLongTermMemoryProviders.LOW_STORE, store);
        assertNotSame(TestLongTermMemoryProviders.HIGH_STORE, store);
    }

    @Test
    public void nullFrameworkFallsBackToInMemory() {
        assertInstanceOf(InMemoryLongTermMemory.class, LongTermMemories.resolve(null));
    }

    @Test
    public void propertyBagBridgedStoreWins() {
        // Even with an available provider, the container-bridged store wins.
        TestLongTermMemoryProviders.HIGH_AVAILABLE.set(true);
        var bridged = new InMemoryLongTermMemory(3);
        props.put(LongTermMemories.STORE_PROPERTY, bridged);

        assertSame(bridged, LongTermMemories.resolve(framework));
    }

    @Test
    public void availableProviderBeatsFallback() {
        TestLongTermMemoryProviders.LOW_AVAILABLE.set(true);

        assertSame(TestLongTermMemoryProviders.LOW_STORE, LongTermMemories.resolve(framework));
    }

    @Test
    public void highestPriorityAvailableProviderWins() {
        TestLongTermMemoryProviders.LOW_AVAILABLE.set(true);
        TestLongTermMemoryProviders.HIGH_AVAILABLE.set(true);

        assertSame(TestLongTermMemoryProviders.HIGH_STORE, LongTermMemories.resolve(framework));
    }

    @Test
    public void unavailableProviderIsSkipped() {
        TestLongTermMemoryProviders.HIGH_AVAILABLE.set(false);
        TestLongTermMemoryProviders.LOW_AVAILABLE.set(true);

        assertSame(TestLongTermMemoryProviders.LOW_STORE, LongTermMemories.resolve(framework));
    }

    // --- withPresetLongTermMemory: the shared @AiEndpoint/@Agent/@Coordinator
    // attach point (Mode Parity) ---

    private HarnessPreset enabledPreset() {
        var cfg = framework.getAtmosphereConfig();
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
        return HarnessPreset.install(framework);
    }

    @Test
    public void presetAttachAppendsInterceptorAndPublishesState() {
        var preset = enabledPreset();

        // presetApplies=true: the caller's resolved decision
        // (featuresFor(...).contains(MEMORY)) — the helper attaches on that alone.
        var result = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, true, "/chat", framework);

        assertEquals(1, result.size());
        assertInstanceOf(LongTermMemoryInterceptor.class, result.get(0));
        assertTrue(preset.runtimeState().get(HarnessPreset.PRIMITIVE_LONG_TERM_MEMORY)
                        .startsWith("ACTIVE("),
                "attach must upgrade the published long-term-memory state");
    }

    @Test
    public void presetAttachAppendsWhenAnnotationDrivenEvenThoughAppWideUnset() {
        // The @Agent {ALL} default / harness={MEMORY} case: the app-wide switch
        // is UNSET (default preset), but the caller resolved MEMORY from the
        // annotation for this endpoint — memory must attach.
        var preset = HarnessPreset.install(framework);
        assertFalse(preset.featuresFor("/deep", new Harness[0], false).contains(Harness.MEMORY),
                "the app-wide switch is unset in this setup");

        var result = LongTermMemories.withPresetLongTermMemory(
                List.of(), preset, true, "/deep", framework);

        assertEquals(1, result.size(),
                "an annotation-driven attach must work with the app-wide switch unset");
        assertInstanceOf(LongTermMemoryInterceptor.class, result.get(0));
    }

    @Test
    public void presetAttachSkipsWhenUserInterceptorPresent() {
        var preset = enabledPreset();
        var user = new LongTermMemoryInterceptor(new InMemoryLongTermMemory(),
                MemoryExtractionStrategy.onSessionClose(), null);
        List<AiInterceptor> declared = List.of(user);

        assertSame(declared, LongTermMemories.withPresetLongTermMemory(
                declared, preset, true, "/chat", framework),
                "a user-declared interceptor is authoritative — no preset append");
    }

    @Test
    public void presetAttachNoOpWhenPresetDoesNotApply() {
        var preset = HarnessPreset.install(framework);

        List<AiInterceptor> none = List.of();
        assertSame(none, LongTermMemories.withPresetLongTermMemory(
                none, preset, false, "/anywhere", framework),
                "presetApplies=false is a byte-identical no-op");
    }
}
