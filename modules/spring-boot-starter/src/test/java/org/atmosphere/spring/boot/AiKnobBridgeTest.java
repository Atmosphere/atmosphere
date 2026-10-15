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
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
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
    void aBeanThatResolvesTheRuntimeWhileCreatedGetsTheNamedOne() {
        contextRunner.withConfiguration(AutoConfigurations.of(InteractionsAutoConfiguration.class))
                .withPropertyValues("atmosphere.ai.runtime=built-in")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = context.getBean(org.atmosphere.interactions.InteractionService.class);
                    var runtime = (org.atmosphere.ai.AgentRuntime)
                            org.springframework.test.util.ReflectionTestUtils.getField(service, "runtime");
                    assertThat(runtime).isNotNull();
                    assertThat(runtime.name()).isEqualTo("built-in");
                });
    }

    /**
     * The application's own beans are created before the auto-configuration's,
     * so a knob bridge that were an ordinary bean would install too late for
     * them: the runtime a user bean resolves while it is created is the one
     * {@code atmosphere.ai.runtime} names, not the highest-priority one.
     */
    @Test
    void aUserBeanThatResolvesTheRuntimeWhileCreatedGetsTheNamedOne() {
        contextRunner.withUserConfiguration(RuntimeResolvingConfiguration.class)
                .withPropertyValues("atmosphere.ai.runtime=built-in")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("resolvedAtCreation", String.class)).isEqualTo("built-in");
                });
    }

    /**
     * An application-supplied {@code LlmSettings} bean built with
     * {@code AiConfig.configure} carries the generation knobs of the
     * application's configuration, as the starter's own bean does.
     */
    @Test
    void anApplicationLlmSettingsBeanCarriesTheGenerationKnobs() {
        contextRunner.withUserConfiguration(UserSettingsConfiguration.class)
                .withPropertyValues("atmosphere.ai.temperature=0.2", "atmosphere.ai.prompt-cache-key=off")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(AiConfig.LlmSettings.class);
                    assertThat(settings.model()).isEqualTo("user-model");
                    assertThat(settings.generation().temperature()).isEqualTo(0.2);
                    assertThat(settings.promptCacheKeyMode())
                            .isEqualTo(org.atmosphere.ai.llm.PromptCacheKeyMode.DISABLED);
                });
    }

    /**
     * Settings built before the context (in {@code main()}, say) keep the
     * generation knobs they were built with: startup names the knobs of the
     * application's configuration they do not apply instead of ignoring them.
     */
    @Test
    void settingsBuiltBeforeTheContextAreReportedAtStartup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv(AiConfig.TEMPERATURE_ENV) == null,
                "LLM_TEMPERATURE set");
        AiConfig.configure("fake", "built-in-main", null, null);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var aiConfigLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiConfig.class);
        appender.start();
        aiConfigLogger.addAppender(appender);
        try {
            contextRunner.withUserConfiguration(EarlySettingsConfiguration.class)
                    .withPropertyValues("atmosphere.ai.temperature=0.2")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(appender.list)
                                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                                .anySatisfy(m -> assertThat(m)
                                        .contains("do not apply [" + AiConfig.TEMPERATURE_PROPERTY + "]"));
                    });
        } finally {
            aiConfigLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class EarlySettingsConfiguration {
        @org.springframework.context.annotation.Bean
        AiConfig.LlmSettings earlySettings() {
            return AiConfig.get();
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class RuntimeResolvingConfiguration {
        @org.springframework.context.annotation.Bean
        String resolvedAtCreation() {
            return AgentRuntimeResolver.resolve().name();
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class UserSettingsConfiguration {
        @org.springframework.context.annotation.Bean
        AiConfig.LlmSettings userLlmSettings() {
            return AiConfig.configure("fake", "user-model", null, null);
        }
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
