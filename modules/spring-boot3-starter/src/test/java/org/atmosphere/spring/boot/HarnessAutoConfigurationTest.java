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
package org.atmosphere.spring.boot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.DurableRunSpineHolder;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 3 parity for the harness preset bridge: the
 * {@code atmosphere.ai.harness.*} properties land as
 * {@code org.atmosphere.ai.*} framework init-params. The enabled switch is
 * tri-state — an absent property bridges nothing (annotation defaults apply),
 * an explicit {@code false} bridges as the kill switch, and {@code true}
 * implies {@code atmosphere.durable-runs.enabled} unless the operator set
 * that property explicitly (explicit {@code false} wins, explicit
 * {@code true} still works without the preset). Mirrors the SB4 starter's
 * {@code HarnessAutoConfigurationTest}.
 */
class HarnessAutoConfigurationTest {

    private final WebApplicationContextRunner aiOnlyRunner = new WebApplicationContextRunner()
            .withBean(RecordingFramework.class)
            .withConfiguration(AutoConfigurations.of(AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    private final WebApplicationContextRunner fullRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    @BeforeEach
    @AfterEach
    void reset() {
        DurableRunSpineHolder.reset();
        DurableRunScopeHolder.clear();
    }

    @Test
    void enabledPresetBridgesAllInitParams() {
        aiOnlyRunner
                .withPropertyValues(
                        "atmosphere.ai.harness.enabled=true",
                        "atmosphere.ai.harness.exclude-paths=/atmosphere/ops,/atmosphere/health",
                        "atmosphere.ai.harness.compaction=summarizing",
                        "atmosphere.ai.harness.prompt-cache-default=conservative",
                        // Keep the implied durable-runs spine off disk in tests.
                        "atmosphere.durable-runs.journal=memory")
                .run(context -> {
                    var recorded = context.getBean(RecordingFramework.class).recorded;
                    // Exact strings from the spec — shared with the Quarkus and
                    // plain-servlet bridges (the cross-container contract).
                    assertThat(recorded)
                            .containsEntry("org.atmosphere.ai.harness.enabled", "true")
                            .containsEntry("org.atmosphere.ai.harness.exclude-paths",
                                    "/atmosphere/ops,/atmosphere/health")
                            .containsEntry("org.atmosphere.ai.compaction", "summarizing")
                            .containsEntry("org.atmosphere.ai.prompt-cache.default", "conservative");
                });
    }

    @Test
    void enabledPresetWithoutOptionalValuesBridgesOnlyTheSwitch() {
        aiOnlyRunner
                .withPropertyValues(
                        "atmosphere.ai.harness.enabled=true",
                        "atmosphere.durable-runs.journal=memory")
                .run(context -> {
                    var recorded = context.getBean(RecordingFramework.class).recorded;
                    assertThat(recorded)
                            .containsEntry(AtmosphereAiAutoConfiguration.HARNESS_ENABLED_PARAM,
                                    "true")
                            .doesNotContainKey(
                                    AtmosphereAiAutoConfiguration.HARNESS_EXCLUDE_PATHS_PARAM)
                            .doesNotContainKey(AtmosphereAiAutoConfiguration.COMPACTION_PARAM)
                            .doesNotContainKey(
                                    AtmosphereAiAutoConfiguration.PROMPT_CACHE_DEFAULT_PARAM);
                });
    }

    @Test
    void unsetSwitchBridgesNoHarnessInitParams() {
        aiOnlyRunner.run(context -> {
            var recorded = context.getBean(RecordingFramework.class).recorded;
            assertThat(recorded)
                    .doesNotContainKey(AtmosphereAiAutoConfiguration.HARNESS_ENABLED_PARAM)
                    .doesNotContainKey(AtmosphereAiAutoConfiguration.HARNESS_EXCLUDE_PATHS_PARAM)
                    .doesNotContainKey(AtmosphereAiAutoConfiguration.COMPACTION_PARAM)
                    .doesNotContainKey(AtmosphereAiAutoConfiguration.PROMPT_CACHE_DEFAULT_PARAM);
        });
    }

    @Test
    void explicitFalseBridgesTheKillSwitch() {
        aiOnlyRunner
                .withPropertyValues("atmosphere.ai.harness.enabled=false")
                .run(context -> {
                    var recorded = context.getBean(RecordingFramework.class).recorded;
                    assertThat(recorded)
                            .as("an explicit false must reach the runtime as the kill switch")
                            .containsEntry(AtmosphereAiAutoConfiguration.HARNESS_ENABLED_PARAM,
                                    "false");
                });
    }

    @Test
    void excludePathsAndSeamsBridgeUnderTheUnsetDefault() {
        // Exclude-paths beat the @Agent batteries-included default, and the
        // compaction / prompt-cache seams are independent of the switch — all
        // three must reach the runtime while the tri-state switch stays absent.
        aiOnlyRunner
                .withPropertyValues(
                        "atmosphere.ai.harness.exclude-paths=/atmosphere/ops",
                        "atmosphere.ai.harness.compaction=summarizing",
                        "atmosphere.ai.harness.prompt-cache-default=conservative")
                .run(context -> {
                    var recorded = context.getBean(RecordingFramework.class).recorded;
                    assertThat(recorded)
                            .doesNotContainKey(AtmosphereAiAutoConfiguration.HARNESS_ENABLED_PARAM)
                            .containsEntry(
                                    AtmosphereAiAutoConfiguration.HARNESS_EXCLUDE_PATHS_PARAM,
                                    "/atmosphere/ops")
                            .containsEntry(AtmosphereAiAutoConfiguration.COMPACTION_PARAM,
                                    "summarizing")
                            .containsEntry(AtmosphereAiAutoConfiguration.PROMPT_CACHE_DEFAULT_PARAM,
                                    "conservative");
                });
    }

    @Test
    void presetImpliesDurableRunsWhenPropertyUnset() {
        fullRunner
                .withPropertyValues(
                        "atmosphere.ai.harness.enabled=true",
                        "atmosphere.durable-runs.journal=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
                    assertThat(DurableRunSpineHolder.get().enabled())
                            .as("the preset implies durable runs when the property is unset")
                            .isTrue();
                });
    }

    @Test
    void explicitFalseBeatsThePresetImplication() {
        fullRunner
                .withPropertyValues(
                        "atmosphere.ai.harness.enabled=true",
                        "atmosphere.durable-runs.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
                    assertThat(DurableRunSpineHolder.get().enabled())
                            .as("an explicit operator opt-out survives the preset")
                            .isFalse();
                });
    }

    @Test
    void killSwitchDoesNotImplyDurableRuns() {
        fullRunner
                .withPropertyValues("atmosphere.ai.harness.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
                    assertThat(DurableRunSpineHolder.get().enabled()).isFalse();
                });
    }

    @Test
    void explicitTrueStillWorksWithoutThePreset() {
        fullRunner
                .withPropertyValues(
                        "atmosphere.durable-runs.enabled=true",
                        "atmosphere.durable-runs.journal=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
                    assertThat(DurableRunSpineHolder.get().enabled()).isTrue();
                });
    }

    @Test
    void longTermMemoryBeanIsBridgedIntoStoreProperty() {
        var store = new org.atmosphere.ai.memory.InMemoryLongTermMemory(5);
        aiOnlyRunner
                .withBean(org.atmosphere.ai.memory.LongTermMemory.class, () -> store)
                .run(context -> {
                    var framework = context.getBean(AtmosphereFramework.class);
                    assertThat(framework.getAtmosphereConfig().properties())
                            .containsEntry(
                                    org.atmosphere.ai.memory.LongTermMemories.STORE_PROPERTY,
                                    store);
                    assertThat(context).hasSingleBean(
                            AtmosphereAiAutoConfiguration.LongTermMemoryBridge.class);
                });
    }

    @Test
    void noLongTermMemoryBeanMeansNoBridge() {
        aiOnlyRunner.run(context -> {
            var framework = context.getBean(AtmosphereFramework.class);
            assertThat(framework.getAtmosphereConfig().properties())
                    .doesNotContainKey(org.atmosphere.ai.memory.LongTermMemories.STORE_PROPERTY);
            assertThat(context).doesNotHaveBean(
                    AtmosphereAiAutoConfiguration.LongTermMemoryBridge.class);
        });
    }

    @Test
    void bothUnsetInstallsNoSpine() {
        fullRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    AtmosphereAiAutoConfiguration.DurableRunSpineInstaller.class);
            assertThat(DurableRunSpineHolder.get().enabled()).isFalse();
        });
    }

    /**
     * Bare framework capturing {@code addInitParameter} calls so the bridge can
     * be asserted without initializing the servlet (init-params are only
     * readable through the framework's {@code ServletConfig} after init).
     */
    static class RecordingFramework extends AtmosphereFramework {

        final Map<String, String> recorded = new ConcurrentHashMap<>();

        @Override
        public AtmosphereFramework addInitParameter(String name, String value) {
            recorded.put(name, value);
            return super.addInitParameter(name, value);
        }
    }
}
