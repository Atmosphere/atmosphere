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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code atmosphere.ai.*} knob set in the Spring {@code Environment}
 * ({@code application.yml}, {@code application.properties}) reaches
 * {@link AiConfig}, {@code atmosphere.ai.runtime} selects the
 * {@code AgentRuntime}, and a JVM system property still wins.
 */
class AiKnobBridgeTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.TEMPERATURE_PROPERTY);
        AiConfig.resetHostConfig();
        AgentRuntimeResolver.reset();
    }

    /** Walks the authoritative list, so a knob added to AiConfig is covered here too. */
    @Test
    void everyKnobSetInTheEnvironmentIsVisibleThroughAiConfig() {
        var properties = new ArrayList<String>();
        for (var property : AiConfig.knobProperties()) {
            properties.add(property + "=" + valueFor(property));
        }
        contextRunner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            for (var knob : AiConfig.knobs()) {
                var env = System.getenv(knob.env());
                var expected = env != null && !env.isBlank() ? env.trim() : valueFor(knob.property());
                assertThat(AiConfig.knob(knob.property())).as(knob.property()).contains(expected);
            }
        });
        assertThat(AiConfig.knob(AiConfig.TOP_P_PROPERTY))
                .as("closing the context uninstalls its knobs")
                .isEqualTo(java.util.Optional.ofNullable(System.getenv(AiConfig.TOP_P_ENV))
                        .filter(v -> !v.isBlank()).map(String::trim));
    }

    private static String valueFor(String property) {
        // The runtime knob must name a runtime that exists, or startup fails.
        return AiConfig.RUNTIME_PROPERTY.equals(property) ? "built-in" : "value-of-" + property;
    }

    @Test
    void generationKnobsFromApplicationConfigReachTheSettings() {
        contextRunner.withPropertyValues(
                        "atmosphere.ai.temperature=0.2",
                        "atmosphere.ai.max-tokens=512",
                        "atmosphere.ai.stop[0]=END",
                        "atmosphere.ai.stop[1]=STOP")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var generation = context.getBean(AiConfig.LlmSettings.class).generation();
                    assertThat(generation.temperature()).isEqualTo(0.2);
                    assertThat(generation.maxTokens()).isEqualTo(512);
                    assertThat(generation.stop()).isEqualTo(List.of("END", "STOP"));
                });
    }

    @Test
    void aJvmSystemPropertyWinsOverApplicationConfig() {
        System.setProperty(AiConfig.TEMPERATURE_PROPERTY, "0.9");
        contextRunner.withPropertyValues("atmosphere.ai.temperature=0.2").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiConfig.LlmSettings.class).generation().temperature())
                    .isEqualTo(0.9);
        });
    }

    @Test
    void theRuntimePropertySelectsTheAgentRuntime() {
        contextRunner.withPropertyValues("atmosphere.ai.runtime=built-in").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AgentRuntimeResolver.resolve().name()).isEqualTo("built-in");
        });
    }

    @Test
    void aRuntimeThatIsNotAvailableFailsStartup() {
        contextRunner.withPropertyValues("atmosphere.ai.runtime=spring-ai").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("atmosphere.ai.runtime=spring-ai names no available AgentRuntime")
                    .hasMessageContaining("built-in");
        });
    }
}
