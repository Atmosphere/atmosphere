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
package org.atmosphere.spring.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Proves the Atmosphere 4 blog (§12) claim for {@code atmosphere-ai-spring-boot-starter}:
 * pulling this <em>single</em> starter is enough to run an {@code @Agent}.
 *
 * <p>This module declares no direct dependency on {@code atmosphere-ai},
 * {@code atmosphere-agent}, or {@code atmosphere-coordinator} — the
 * {@link org.atmosphere.spring.boot.ai.agent.OneDependencyAgent @Agent} fixture
 * and every assertion type below reach the test classpath only because the
 * starter pins those modules <strong>non-optionally</strong>. The base
 * {@code atmosphere-spring-boot-starter} declares them
 * {@code <optional>true</optional>} (transport-first), so it alone could not run
 * an {@code @Agent}.</p>
 *
 * <p><strong>Bean presence is the wiring proof.</strong> Each assertion pulls a
 * representative type straight out of the live {@link ApplicationContext} or
 * inspects the running {@link AtmosphereFramework} — never the classpath. If the
 * starter had forgotten to drag a layer in (or left it optional), the
 * corresponding bean lookup / handler lookup would fail and this test would go
 * red.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Point the framework's annotation scanner at the @Agent fixture's
                // package so AgentProcessor registers it during framework.init().
                "atmosphere.packages=org.atmosphere.spring.boot.ai.agent",
                // No real LLM in CI — boot the AI config in warn-not-throw mode.
                "atmosphere.ai.fail-fast=false"
        })
class OneDependencyAgentWiringTest {

    /** The path AgentProcessor registers a non-headless {@code @Agent} web handler at. */
    private static final String AGENT_HANDLER_PATH = "/atmosphere/agent/oneDepAgent";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AtmosphereFramework framework;

    /**
     * Runtime + AI pipeline + agent processing + Console all wire from the one
     * starter dependency, as live beans / live framework registrations.
     */
    @Test
    void oneStarterDependencyRunsAnAgent() {
        // 1 — Runtime: the core framework is live and not torn down.
        assertThat(framework)
                .as("runtime: AtmosphereFramework auto-configured by the starter")
                .isNotNull();
        assertThat(framework.isDestroyed())
                .as("runtime: framework is running, not destroyed")
                .isFalse();

        // 2 — AI pipeline: the resolved LLM settings + the endpoint registrar that
        // AtmosphereAiAutoConfiguration only contributes when atmosphere-ai is on
        // the classpath. The registrar is what bridges guardrails / RAG- and
        // memory-injection safety into the framework, i.e. the AI pipeline itself.
        assertThat(context.getBean(AiConfig.LlmSettings.class))
                .as("AI pipeline: AiConfig.LlmSettings auto-configured by the starter")
                .isNotNull();
        // AtmosphereAiEndpointRegistrar is package-private in the base starter;
        // assert it by bean name. Its presence proves AtmosphereAiAutoConfiguration
        // fired — the registrar is what bridges guardrails / RAG- and memory-
        // injection safety into the framework, i.e. the AI pipeline itself.
        assertThat(context.containsBean("atmosphereAiEndpointRegistrar"))
                .as("AI pipeline: atmosphereAiEndpointRegistrar wired (AtmosphereAiAutoConfiguration fired)")
                .isTrue();

        // 3 — Agent processing: AgentProcessor ran against the single @Agent class
        // and registered its web streaming handler. This is a runtime fact read
        // off the live handler registry, not a classpath check.
        assertThat(framework.getAtmosphereHandlers().keySet())
                .as("agent: AgentProcessor registered the @Agent host at " + AGENT_HANDLER_PATH)
                .anyMatch(path -> path.contains(AGENT_HANDLER_PATH));

        // 4 — Console: the base starter's console resource filter is live, so the
        // Atmosphere Console is served from this single dependency.
        assertThat(context.containsBean("atmosphereConsoleFilter"))
                .as("console: atmosphereConsoleFilter registered (Console auto-config live)")
                .isTrue();

        // 5 — Coordinator: the admin coordinator controller bean exists only
        // because atmosphere-coordinator (AgentFleet) rode in non-optionally on
        // the starter; with the base starter alone it would be absent.
        assertThat(context.getBean(CoordinatorController.class))
                .as("coordinator: CoordinatorController wired because atmosphere-coordinator is non-optional")
                .isNotNull();
    }

    @SpringBootApplication
    static class TestApp {
    }
}
