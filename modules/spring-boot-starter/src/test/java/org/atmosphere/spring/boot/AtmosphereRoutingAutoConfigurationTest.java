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
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pins the opt-in wiring of property-driven content routing (F3a): absent by
 * default (the resolved client is left untouched), present and installed as the
 * {@link AiConfig} client when {@code atmosphere.ai.routing.enabled=true}. The
 * {@code @Bean} is the production consumer that makes {@link RoutingLlmClient} a
 * real part of the request critical path rather than an unused SPI.
 */
class AtmosphereRoutingAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            // fake mode boots without a key and resolves a concrete, no-network
            // FakeLlmClient — so cost/latency/model rules that reuse the
            // resolved client can be driven deterministically without an
            // outbound call.
            .withPropertyValues("atmosphere.ai.mode=fake",
                    "atmosphere.ai.model=llama3.2");

    @AfterEach
    void resetGlobalClient() {
        // The router/resolved client is installed into the process-wide
        // AiConfig singleton; the installer's DisposableBean restores it on
        // context close, but reset defensively so a failed context cannot leak
        // a router into a sibling test.
        AiConfig.configure("fake", "llama3.2", null, null);
    }

    @Test
    void offByDefaultLeavesPlainClient() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
            // The resolved client must NOT be a RoutingLlmClient when disabled.
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
                        "atmosphere.ai.routing.content-rules[0].keywords[1]=refactor",
                        "atmosphere.ai.routing.content-rules[0].model=gpt-4o")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount())
                            .as("the single valid content rule must be installed")
                            .isEqualTo(1);
                    // The decisive assertion: the client every AgentRuntime
                    // dispatch reads is now the RoutingLlmClient — a real
                    // consumer on the request critical path.
                    assertThat(AiConfig.get().client())
                            .as("routing.enabled must install RoutingLlmClient as the AiConfig client")
                            .isInstanceOf(RoutingLlmClient.class);
                    assertThat(AiConfig.get().client()).isSameAs(installer.router());
                });
    }

    @Test
    void enabledWithNoRulesStillInstallsRouterAsPassthrough() {
        contextRunner
                .withPropertyValues("atmosphere.ai.routing.enabled=true")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount()).isZero();
                    // Even with zero rules, the router is installed; it simply
                    // falls through to the resolved client + default model.
                    assertThat(AiConfig.get().client()).isInstanceOf(RoutingLlmClient.class);
                });
    }

    @Test
    void invalidRuleIsSkippedNotInstalled() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // rule with keywords but NO model -> skipped
                        "atmosphere.ai.routing.content-rules[0].keywords[0]=code",
                        // rule with model but NO keywords -> skipped
                        "atmosphere.ai.routing.content-rules[1].model=gpt-4o",
                        // valid rule -> kept
                        "atmosphere.ai.routing.content-rules[2].keywords[0]=image",
                        "atmosphere.ai.routing.content-rules[2].model=gpt-4o-mini")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount())
                            .as("only the one valid rule survives the skip filter")
                            .isEqualTo(1);
                });
    }

    @Test
    void costRuleInstallsAndRoutesByBudget() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // expensive (cap 10) is out of budget at maxStreamingTexts=2048;
                        // cheap (cap 5) fits → cheap is the highest-capability survivor.
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
                    // maxStreamingTexts=2048 (default): expensive 0.01*2048=20.48 > 5.0,
                    // cheap 0.001*2048=2.048 <= 5.0.
                    installer.router().streamChatCompletion(
                            ChatCompletionRequest.of("ignored", "hello"), session);

                    verify(session).sendMetadata("routing.model", "cheap");
                    verify(session).sendMetadata("routing.cost", 0.001 * 2048);
                });
    }

    @Test
    void latencyRuleInstallsAndRoutesByLatency() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // slow (cap 10) exceeds the 100ms budget; fast (cap 5) fits.
                        "atmosphere.ai.routing.latency-rules[0].max-latency-ms=100",
                        "atmosphere.ai.routing.latency-rules[0].models[0].model=slow",
                        "atmosphere.ai.routing.latency-rules[0].models[0].average-latency-ms=500",
                        "atmosphere.ai.routing.latency-rules[0].models[0].capability=10",
                        "atmosphere.ai.routing.latency-rules[0].models[1].model=fast",
                        "atmosphere.ai.routing.latency-rules[0].models[1].average-latency-ms=50",
                        "atmosphere.ai.routing.latency-rules[0].models[1].capability=5")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount()).isEqualTo(1);

                    var session = mock(StreamingSession.class);
                    installer.router().streamChatCompletion(
                            ChatCompletionRequest.of("ignored", "hello"), session);

                    verify(session).sendMetadata("routing.model", "fast");
                    verify(session).sendMetadata("routing.latency", 50L);
                });
    }

    @Test
    void modelRuleRoutesByModelNameLeavingRequestUnchanged() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // Case-insensitive literal equals on request.model().
                        "atmosphere.ai.routing.model-rules[0].model-pattern=GPT-4O")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount()).isEqualTo(1);

                    // request.model() = "gpt-4o" matches "GPT-4O" case-insensitively.
                    var session = mock(StreamingSession.class);
                    var request = ChatCompletionRequest.of("gpt-4o", "hello");
                    installer.router().streamChatCompletion(request, session);

                    // ModelBased routes the ORIGINAL request unchanged: the
                    // router emits routing.model = the un-rewritten request model
                    // (not a routed/rewritten name like cost/latency/content do).
                    verify(session).sendMetadata("routing.model", "gpt-4o");
                    assertThat(request.model())
                            .as("the request model must not be rewritten by a model rule")
                            .isEqualTo("gpt-4o");
                });
    }

    @Test
    void mixedFamiliesComposeInOrderContentFirst() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // content (1) + model (1) + cost (1) + latency (1) = ruleCount 4.
                        "atmosphere.ai.routing.content-rules[0].keywords[0]=refactor",
                        "atmosphere.ai.routing.content-rules[0].model=content-model",
                        "atmosphere.ai.routing.model-rules[0].model-pattern=some-model",
                        "atmosphere.ai.routing.cost-rules[0].max-cost=100.0",
                        "atmosphere.ai.routing.cost-rules[0].models[0].model=cost-model",
                        "atmosphere.ai.routing.cost-rules[0].models[0].cost-per-streaming-text=0.0",
                        "atmosphere.ai.routing.cost-rules[0].models[0].capability=5",
                        "atmosphere.ai.routing.latency-rules[0].max-latency-ms=10000",
                        "atmosphere.ai.routing.latency-rules[0].models[0].model=latency-model",
                        "atmosphere.ai.routing.latency-rules[0].models[0].average-latency-ms=1",
                        "atmosphere.ai.routing.latency-rules[0].models[0].capability=5")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount())
                            .as("ruleCount must sum all four families")
                            .isEqualTo(4);

                    // A "refactor" prompt hits the content rule first even though
                    // the cost+latency rules would also match anything — content
                    // is composed before model/cost/latency.
                    var session = mock(StreamingSession.class);
                    installer.router().streamChatCompletion(
                            ChatCompletionRequest.of("ignored", "please refactor this"), session);
                    verify(session).sendMetadata("routing.model", "content-model");
                });
    }

    @Test
    void invalidCostRuleSkipped() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // max-cost set but models empty -> skipped.
                        "atmosphere.ai.routing.cost-rules[0].max-cost=5.0")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount())
                            .as("a cost rule with empty models is skipped")
                            .isZero();
                });
    }

    @Test
    void invalidModelRuleSkipped() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.routing.enabled=true",
                        // blank model-pattern -> skipped.
                        "atmosphere.ai.routing.model-rules[0].model-pattern=")
                .run(context -> {
                    var installer = context.getBean(
                            AtmosphereAiAutoConfiguration.RoutingClientInstaller.class);
                    assertThat(installer.ruleCount())
                            .as("a model rule with a blank pattern is skipped")
                            .isZero();
                });
    }
}
