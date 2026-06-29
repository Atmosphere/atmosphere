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
package org.atmosphere.samples.adminbundle;

import static org.assertj.core.api.Assertions.assertThat;

import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.rag.InMemoryContextProvider;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.session.DurableSessionInterceptor;
import org.atmosphere.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Proves the Atmosphere 4 blog (§12) claim about {@code atmosphere-admin-bundle}:
 * pulling that <em>single</em> dependency (this module declares no other
 * {@code org.atmosphere.*} artifact) brings the six families together as live
 * beans in one Spring context.
 *
 * <p>For an aggregator bundle, <strong>bean presence is the wiring proof</strong>:
 * each assertion below pulls a representative type straight out of the live
 * {@link ApplicationContext}, not off the classpath. If the bundle had forgotten
 * to drag a family's auto-configuration in, the corresponding {@code getBean}
 * would throw {@code NoSuchBeanDefinitionException} and this test would fail.</p>
 *
 * <p>All six families auto-configure live beans from the bundle alone — runtime,
 * AI, coordinator, durable sessions, plus RAG and checkpoints. The latter two are
 * backed by safe in-memory <em>defaults</em>:
 * {@code AtmosphereContextProviderAutoConfiguration} contributes an empty
 * {@link InMemoryContextProvider} and {@code AtmosphereCheckpointAutoConfiguration}
 * contributes an {@link InMemoryCheckpointStore}, each gated
 * {@code @ConditionalOnMissingBean} so an operator-supplied provider/store still
 * wins. Asserting the default bean <em>type</em> (the in-memory impls) is the
 * proof that the permissive-default path fired — and that path logs the startup
 * {@code WARN} immediately before returning the bean, so the type assertion
 * transitively proves the warning was emitted. The warnings are also visible in
 * the test's captured startup log.</p>
 */
@SpringBootTest(
        properties = {
                // No real LLM in CI — boot AI config in warn-not-throw mode.
                "atmosphere.ai.fail-fast=false",
                // Durable sessions auto-config is opt-in; turn it on so the
                // SessionStore + DurableSessionInterceptor beans materialize.
                "atmosphere.durable-sessions.enabled=true"
        })
class AdminBundleWiringTest {

    @Autowired
    private ApplicationContext context;

    /**
     * All six families wire a representative live bean from the bundle alone.
     * Every {@code getBean} here resolves against the running context.
     */
    @Test
    void bundleAutoWiresAllSixFamiliesAsLiveBeans() {
        // Family 1 — Runtime: the core framework, wired by AtmosphereAutoConfiguration.
        AtmosphereFramework framework = context.getBean(AtmosphereFramework.class);
        assertThat(framework)
                .as("runtime family: AtmosphereFramework auto-configured by the bundle")
                .isNotNull();

        // Family 2 — AI: the resolved LLM settings bean, wired by AtmosphereAiAutoConfiguration.
        AiConfig.LlmSettings aiSettings = context.getBean(AiConfig.LlmSettings.class);
        assertThat(aiSettings)
                .as("AI family: AiConfig.LlmSettings (AI pipeline config) auto-configured by the bundle")
                .isNotNull();

        // Family 3 — Coordinator: the admin coordinator controller only exists
        // because atmosphere-coordinator (AgentFleet) rode in on the bundle.
        CoordinatorController coordinatorController = context.getBean(CoordinatorController.class);
        assertThat(coordinatorController)
                .as("coordinator family: CoordinatorController wired because atmosphere-coordinator is on the bundle")
                .isNotNull();

        // Family 4 — RAG: a live ContextProvider bean, backed by the in-memory
        // default from AtmosphereContextProviderAutoConfiguration (no vector
        // store supplied → the @ConditionalOnMissingBean default fires + WARNs).
        ContextProvider contextProvider = context.getBean(ContextProvider.class);
        assertThat(contextProvider)
                .as("RAG family: ContextProvider auto-configured as the in-memory default by the bundle")
                .isInstanceOf(InMemoryContextProvider.class);

        // Family 5 — Checkpoints: a live CheckpointStore bean, backed by the
        // in-memory default from AtmosphereCheckpointAutoConfiguration (no store
        // supplied → the @ConditionalOnMissingBean default fires + WARNs).
        CheckpointStore checkpointStore = context.getBean(CheckpointStore.class);
        assertThat(checkpointStore)
                .as("checkpoint family: CheckpointStore auto-configured as the in-memory default by the bundle")
                .isInstanceOf(InMemoryCheckpointStore.class);

        // Family 6 — Durable sessions: store + interceptor, wired by DurableSessionAutoConfiguration.
        SessionStore sessionStore = context.getBean(SessionStore.class);
        DurableSessionInterceptor durableSessionInterceptor =
                context.getBean(DurableSessionInterceptor.class);
        assertThat(sessionStore)
                .as("durable-sessions family: SessionStore auto-configured by the bundle")
                .isNotNull();
        assertThat(durableSessionInterceptor)
                .as("durable-sessions family: DurableSessionInterceptor auto-configured by the bundle")
                .isNotNull();
    }

    /**
     * The RAG and checkpoint defaults are gated with {@code @ConditionalOnMissingBean}:
     * because this sample supplies neither a {@code ContextProvider} nor a
     * {@code CheckpointStore} bean, exactly one bean of each type exists — the
     * in-memory default. (An operator who declares their own, as
     * {@code spring-boot-checkpoint-agent} does, would replace it rather than
     * add a second.)
     */
    @Test
    void ragAndCheckpointDefaultsAreSingleConditionalBeans() {
        assertThat(context.getBeanNamesForType(ContextProvider.class))
                .as("RAG family: exactly one ContextProvider bean — the in-memory default")
                .hasSize(1);
        assertThat(context.getBeanNamesForType(CheckpointStore.class))
                .as("checkpoint family: exactly one CheckpointStore bean — the in-memory default")
                .hasSize(1);
    }
}
