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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LogprobsMode;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.llm.PromptCacheKeyMode;
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Config-driven routing parity for the <strong>Spring Boot 3</strong> starter.
 * Mirrors the SB4 {@code AtmosphereRoutingAutoConfigurationTest}: the same
 * {@code atmosphere.ai.routing.*} properties must install a
 * {@link RoutingLlmClient} as the {@code AiConfig} client every
 * {@code AgentRuntime} dispatch reads. Closes the SB3 routing gap so
 * {@code modules/ai/README.md}'s "the Spring Boot starter exposes all four
 * RoutingRule families … no Java wiring required" is true on SB3 too (not a
 * silent no-op).
 */
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
class AtmosphereRoutingAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            // fake mode resolves a concrete, no-network client so cost/latency
            // rules that reuse the resolved client can be driven deterministically.
            .withPropertyValues("atmosphere.ai.mode=fake", "atmosphere.ai.model=llama3.2");

    @AfterEach
    void resetGlobalClient() {
        AiConfig.configure("fake", "llama3.2", null, null);
    }

    @Test
    void offByDefaultLeavesPlainClient() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
            assertThat(AiConfig.get()).isNotNull();
            assertThat(AiConfig.get().client()).isNotInstanceOf(RoutingLlmClient.class);
        });
    }

    @Test
    void enabledInstallsRoutingClientOnTheCriticalPath() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        "atmosphere.ai.routing.default-model=llama3.2",
                        "atmosphere.ai.routing.content-rules[0].keywords[0]=code",
                        "atmosphere.ai.routing.content-rules[0].model=gpt-4o")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount()).isEqualTo(1);
                    assertThat(AiConfig.get().client())
                            .as("routing.enabled must install RoutingLlmClient as the AiConfig client")
                            .isInstanceOf(RoutingLlmClient.class);
                    assertThat(AiConfig.get().client()).isSameAs(installer.router());
                });
    }

    @Test
    void contentRuleRoutesMatchingPromptToConfiguredModel() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        "atmosphere.ai.routing.content-rules[0].keywords[0]=refactor",
                        "atmosphere.ai.routing.content-rules[0].model=code-model")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    var session = mock(StreamingSession.class);
                    installer.router().streamChatCompletion(
                            ChatCompletionRequest.of("ignored", "please refactor this"), session);
                    verify(session).sendMetadata("routing.model", "code-model");
                });
    }

    @Test
    void costRuleRoutesByCostBudget() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        "atmosphere.ai.routing.cost-rules[0].max-cost=5.0",
                        "atmosphere.ai.routing.cost-rules[0].models[0].model=expensive",
                        "atmosphere.ai.routing.cost-rules[0].models[0].cost-per-streaming-text=0.01",
                        "atmosphere.ai.routing.cost-rules[0].models[0].capability=10",
                        "atmosphere.ai.routing.cost-rules[0].models[1].model=cheap",
                        "atmosphere.ai.routing.cost-rules[0].models[1].cost-per-streaming-text=0.001",
                        "atmosphere.ai.routing.cost-rules[0].models[1].capability=5")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount()).isEqualTo(1);
                    var session = mock(StreamingSession.class);
                    installer.router().streamChatCompletion(
                            ChatCompletionRequest.of("ignored", "hello"), session);
                    // expensive 0.01*2048 > 5.0; cheap 0.001*2048 <= 5.0.
                    verify(session).sendMetadata("routing.model", "cheap");
                });
    }

    /**
     * A rule with its own base URL gets a client of its own, built with the
     * prompt-cache-key and logprobs modes of the context's application, not the
     * modes the static API reads when the client is built. Here another
     * application opens while this context starts (parallel startup, a lazy
     * bean), so the static API reads the process default, which has neither
     * knob set.
     */
    @Test
    void aRuleClientOfItsOwnTakesTheContextsModesWhileAnotherApplicationRuns() {
        contextRunner
                .withUserConfiguration(AnotherApplicationOpensDuringStartup.class)
                .withPropertyValues(
                        "atmosphere.ai.logprobs=disabled",
                        "atmosphere.ai.prompt-cache-key=disabled",
                        "atmosphere.ai.routing.enabled=true",
                        "atmosphere.ai.routing.content-rules[0].keywords[0]=code",
                        "atmosphere.ai.routing.content-rules[0].model=gpt-4o",
                        "atmosphere.ai.routing.content-rules[0].base-url=http://127.0.0.1:1/v1")
                .run(context -> {
                    try {
                        var installer = context.getBean(
                                AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                        var rule = (RoutingLlmClient.RoutingRule.ContentBased) rulesOf(installer.router()).get(0);
                        var client = (OpenAiCompatibleClient) rule.target();

                        assertThat(client.logprobsMode())
                                .as("the rule client's logprobs mode is the context's")
                                .isEqualTo(LogprobsMode.DISABLED);
                        assertThat(client.promptCacheKeyMode())
                                .as("the rule client's prompt-cache-key mode is the context's")
                                .isEqualTo(PromptCacheKeyMode.DISABLED);
                    } finally {
                        AnotherApplicationOpensDuringStartup.closeIt();
                    }
                });
    }

    private static java.util.List<?> rulesOf(RoutingLlmClient router) throws ReflectiveOperationException {
        var field = RoutingLlmClient.class.getDeclaredField("rules");
        field.setAccessible(true);
        return (java.util.List<?>) field.get(router);
    }

    /** Opens a second application's environment once this context's is attached. */
    @Configuration(proxyBeanMethods = false)
    static class AnotherApplicationOpensDuringStartup {

        private static final java.util.concurrent.atomic.AtomicReference<AiEnvironment> OPENED =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Bean
        static BeanPostProcessor anotherApplicationOpens() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AtmosphereAiAutoConfiguration.AiEnvironmentAttachment) {
                        OPENED.set(AiEnvironment.create(java.util.Map.<String, String>of()::get));
                    }
                    return bean;
                }
            };
        }

        static void closeIt() {
            var opened = OPENED.getAndSet(null);
            if (opened != null) {
                opened.close();
            }
        }
    }
}
