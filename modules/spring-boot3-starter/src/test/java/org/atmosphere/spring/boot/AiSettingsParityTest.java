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

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring Boot 3 starter resolves the LLM settings as the SB4 starter does:
 * {@code atmosphere.ai.mode}/{@code model}/{@code base-url} fall back to the
 * {@code LLM_MODE}/{@code LLM_MODEL}/{@code LLM_BASE_URL} environment variables,
 * and configuring them drops a runtime resolution cached before (Mode Parity,
 * Correctness Invariant #7).
 */
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
class AiSettingsParityTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        AgentRuntimeResolver.reset();
    }

    /**
     * The mode and model are unset unless configured, so the LLM_MODE /
     * LLM_MODEL environment variables can supply them, as on the SB4 starter.
     */
    @Test
    void modeAndModelHaveNoPropertyDefault() {
        var ai = new AtmosphereProperties.AiProperties();
        assertThat(ai.getMode()).isNull();
        assertThat(ai.getModel()).isNull();
    }

    @Test
    void anUnsetModeFallsBackToTheEnvironmentThenTheDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(AiConfig.LlmSettings.class);
            assertThat(settings.mode()).isEqualTo(envOr("LLM_MODE", AiConfig.DEFAULT_MODE));
            assertThat(settings.model()).isEqualTo(envOr("LLM_MODEL", AiConfig.DEFAULT_MODEL));
        });
    }

    private static String envOr(String name, String fallback) {
        var value = System.getenv(name);
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * A runtime resolution cached before the settings were configured is
     * dropped: with an API key the demo runtime is not available, and with
     * fake mode it is, so a stale cache would keep the built-in one.
     */
    @Test
    void configuringTheSettingsDropsAStaleRuntimeResolution() {
        AiConfig.configure("remote", "gpt-4o", "test-key", null);
        AgentRuntimeResolver.reset();
        assertThat(AgentRuntimeResolver.resolve().name()).isNotEqualTo("demo");

        contextRunner.withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AgentRuntimeResolver.resolve().name())
                            .as("resolution after configure(fake) sees the demo runtime")
                            .isEqualTo("demo");
                });
    }
}
