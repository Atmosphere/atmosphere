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
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

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
            // local mode boots without a key and resolves a concrete client.
            .withPropertyValues("atmosphere.ai.mode=local",
                    "atmosphere.ai.model=llama3.2");

    @AfterEach
    void resetGlobalClient() {
        // The router/resolved client is installed into the process-wide
        // AiConfig singleton; the installer's DisposableBean restores it on
        // context close, but reset defensively so a failed context cannot leak
        // a router into a sibling test.
        AiConfig.configure("local", "llama3.2", null, null);
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
}
