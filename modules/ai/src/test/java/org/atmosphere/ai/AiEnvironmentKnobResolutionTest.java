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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.LogprobsMode;
import org.atmosphere.ai.llm.PromptCacheKeyMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The static knob API resolves every knob exactly as it did before
 * {@link AiEnvironment} existed — system property, then environment variable,
 * then the host configuration installed last — and the process default
 * resolves the same values, so moving the resolution into the environment
 * changes nothing for an application that only uses the statics.
 */
class AiEnvironmentKnobResolutionTest {

    @AfterEach
    void tearDown() {
        for (var knob : AiConfig.knobs()) {
            System.clearProperty(knob.property());
        }
        AiConfig.resetHostConfig();
    }

    /**
     * The resolution the static API had before environments: an independent
     * copy of the old {@code rawKnob(property, env)}.
     */
    private static Optional<String> legacy(AiConfig.Knob knob, Map<String, String> host) {
        var raw = System.getProperty(knob.property());
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(knob.env());
        }
        if (raw == null || raw.isBlank()) {
            raw = host.get(knob.property());
        }
        return Optional.ofNullable(raw != null && !raw.isBlank() ? raw.trim() : null);
    }

    private static void assertResolvedAsBefore(Map<String, String> host, String scenario) {
        for (var knob : AiConfig.knobs()) {
            var expected = legacy(knob, host);
            assertEquals(expected, AiConfig.knob(knob.property()), scenario + ": " + knob.property());
            assertEquals(expected, AiEnvironment.processDefault().knob(knob.property()),
                    scenario + " (process default): " + knob.property());
        }
        var raw = new HashMap<String, String>();
        for (var knob : AiConfig.knobs()) {
            raw.put(knob.property(), legacy(knob, host).orElse(null));
        }
        assertEquals(PromptCacheKeyMode.parse(raw.get(AiConfig.PROMPT_CACHE_KEY_PROPERTY)),
                AiConfig.resolvePromptCacheKeyMode(), scenario);
        assertEquals(LogprobsMode.parse(raw.get(AiConfig.LOGPROBS_PROPERTY)),
                AiConfig.resolveLogprobsMode(), scenario);
        assertEquals(NativeStructuredOutputMode.parse(raw.get(AiConfig.NATIVE_STRUCTURED_OUTPUT_PROPERTY)),
                AiConfig.resolveNativeStructuredOutputMode(), scenario);
        assertEquals(org.atmosphere.ai.plan.PlanningMode.parse(raw.get(AiConfig.PLANNING_PROPERTY)),
                AiConfig.resolvePlanningMode(), scenario);
        assertEquals(org.atmosphere.ai.fs.FilesystemMode.parse(raw.get(AiConfig.FILESYSTEM_PROPERTY)),
                AiConfig.resolveFilesystemMode(), scenario);
        assertEquals(Optional.ofNullable(raw.get(AiConfig.EMBEDDING_MODEL_PROPERTY)),
                AiConfig.resolveEmbeddingModel(), scenario);
        assertEquals(AiConfig.parseGenerationParams(raw.get(AiConfig.TEMPERATURE_PROPERTY),
                        raw.get(AiConfig.MAX_TOKENS_PROPERTY), raw.get(AiConfig.TOP_P_PROPERTY),
                        raw.get(AiConfig.STOP_PROPERTY)),
                AiConfig.resolveGenerationParams(), scenario);
        var grace = raw.get(AiConfig.REATTACH_GRACE_PROPERTY);
        var parsedGrace = grace != null ? AiConfig.parseDuration(grace) : null;
        assertEquals(parsedGrace != null && !parsedGrace.isNegative() ? parsedGrace
                        : AiConfig.DEFAULT_REATTACH_GRACE,
                AiConfig.resolveReattachGrace(), scenario);
    }

    private static Map<String, String> values(String prefix) {
        var values = new HashMap<String, String>();
        for (var knob : AiConfig.knobs()) {
            values.put(knob.property(), switch (knob.property()) {
                case AiConfig.TEMPERATURE_PROPERTY, AiConfig.TOP_P_PROPERTY -> prefix.equals("host") ? "0.25" : "0.75";
                case AiConfig.MAX_TOKENS_PROPERTY -> prefix.equals("host") ? "128" : "256";
                case AiConfig.REATTACH_GRACE_PROPERTY -> prefix.equals("host") ? "5s" : "500ms";
                case AiConfig.PROMPT_CACHE_KEY_PROPERTY, AiConfig.LOGPROBS_PROPERTY,
                        AiConfig.NATIVE_STRUCTURED_OUTPUT_PROPERTY -> prefix.equals("host") ? "disabled" : "enabled";
                case AiConfig.PLANNING_PROPERTY, AiConfig.FILESYSTEM_PROPERTY ->
                        prefix.equals("host") ? "builtin" : "native";
                default -> prefix + "-" + knob.property();
            });
        }
        return values;
    }

    @Test
    void withNothingInstalledEveryKnobResolvesAsBefore() {
        assertResolvedAsBefore(Map.of(), "nothing installed");
    }

    @Test
    void theHostConfigurationReachesEveryKnobAsBefore() {
        var host = values("host");
        AiConfig.installHostConfig(host::get);
        assertResolvedAsBefore(host, "host configuration");
    }

    @Test
    void aSystemPropertyStillWinsOverTheHostConfiguration() {
        var host = values("host");
        AiConfig.installHostConfig(host::get);
        values("sysprop").forEach(System::setProperty);
        assertResolvedAsBefore(host, "system property over host");
    }

    @Test
    void aBlankSystemPropertyFallsThroughToTheHostConfiguration() {
        var host = values("host");
        AiConfig.installHostConfig(host::get);
        for (var knob : AiConfig.knobs()) {
            System.setProperty(knob.property(), "  ");
        }
        assertResolvedAsBefore(host, "blank system property");
    }

    @Test
    void theLatestHostConfigurationIsReadUntilItIsClosed() {
        var first = values("host");
        var second = new HashMap<>(first);
        second.put(AiConfig.TEMPERATURE_PROPERTY, "0.5");
        AiConfig.installHostConfig(first::get);
        var latest = AiConfig.installHostConfig(second::get);
        assertResolvedAsBefore(second, "latest host configuration");
        latest.close();
        assertResolvedAsBefore(first, "previous host configuration after close");
    }

    @Test
    void theStaticApiReadsTheProcessDefaultWhenNoApplicationIsRunning() {
        assertSame(AiEnvironment.processDefault(), AiEnvironment.current());
        AiConfig.configure("fake", "process-model", null, null);
        assertSame(AiConfig.get(), AiEnvironment.processDefault().settings());
    }
}
