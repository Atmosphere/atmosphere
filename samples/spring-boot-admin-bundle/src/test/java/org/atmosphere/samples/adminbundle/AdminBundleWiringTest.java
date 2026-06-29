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
import static org.assertj.core.api.Assertions.assertThatCode;

import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.ai.AiConfig;
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
 * {@code org.atmosphere.*} artifact) brings the six families together in one
 * Spring context.
 *
 * <p>For an aggregator bundle, <strong>bean presence is the wiring proof</strong>:
 * each assertion below pulls a representative type straight out of the live
 * {@link ApplicationContext}, not off the classpath. If the bundle had forgotten
 * to drag a family's auto-configuration in, the corresponding {@code getBean}
 * would throw {@code NoSuchBeanDefinitionException} and this test would fail.</p>
 *
 * <p>Four of the six families auto-configure live beans from the bundle alone:
 * runtime, AI, coordinator, and durable sessions. The remaining two — RAG and
 * checkpoints — are aggregated onto the classpath as discoverable SPIs but do
 * <em>not</em> auto-configure a live bean without one operator-supplied backing
 * bean (a Spring AI {@code VectorStore} for RAG; a {@code CheckpointStore}
 * {@code @Bean} for checkpoints, exactly as the {@code spring-boot-checkpoint-agent}
 * sample wires it). {@link #ragAndCheckpointFamiliesAreAggregatedButNotAutoWired()}
 * asserts that true state rather than pretending a bean exists.</p>
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
     * Family 1 (runtime), 2 (AI), 3 (coordinator), 6 (durable sessions): the
     * bundle auto-configures a representative live bean for each. Every
     * {@code getBean} here resolves against the running context.
     */
    @Test
    void bundleAutoWiresFourFamiliesAsLiveBeans() {
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
     * Family 4 (RAG) and 5 (checkpoints): the bundle genuinely aggregates these
     * modules — their SPI type and a concrete in-tree implementation are loadable
     * from this module's classpath, which has no other {@code org.atmosphere.*}
     * dependency than the bundle. But neither auto-configures a live bean without
     * an operator-supplied backing bean, so we assert exactly that — the SPI is
     * present, and the context holds zero beans of that type. This documents the
     * activation gap honestly instead of faking a bean.
     */
    @Test
    void ragAndCheckpointFamiliesAreAggregatedButNotAutoWired() {
        ClassLoader cl = context.getClassLoader();

        // Family 4 — RAG: ContextProvider SPI + a concrete provider arrive via the bundle...
        assertThatCode(() -> Class.forName("org.atmosphere.ai.ContextProvider", false, cl))
                .as("RAG family: ContextProvider SPI aggregated onto the classpath by the bundle")
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("org.atmosphere.ai.rag.InMemoryContextProvider", false, cl))
                .as("RAG family: a concrete ContextProvider (InMemoryContextProvider) shipped by the bundle")
                .doesNotThrowAnyException();
        // ...but with no Spring AI VectorStore bean, no ContextProvider bean is auto-configured.
        assertThat(beanCountFor("org.atmosphere.ai.ContextProvider", cl))
                .as("RAG family: no ContextProvider bean is auto-wired without a backing vector store")
                .isZero();

        // Family 5 — Checkpoints: CheckpointStore SPI + a concrete store arrive via the bundle...
        assertThatCode(() -> Class.forName("org.atmosphere.checkpoint.CheckpointStore", false, cl))
                .as("checkpoint family: CheckpointStore SPI aggregated onto the classpath by the bundle")
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("org.atmosphere.checkpoint.InMemoryCheckpointStore", false, cl))
                .as("checkpoint family: a concrete CheckpointStore (InMemoryCheckpointStore) shipped by the bundle")
                .doesNotThrowAnyException();
        // ...but checkpoints have no Spring auto-config; the operator declares a @Bean (see spring-boot-checkpoint-agent).
        assertThat(beanCountFor("org.atmosphere.checkpoint.CheckpointStore", cl))
                .as("checkpoint family: no CheckpointStore bean is auto-wired without an operator @Bean")
                .isZero();
    }

    private int beanCountFor(String typeName, ClassLoader cl) {
        try {
            Class<?> type = Class.forName(typeName, false, cl);
            return context.getBeanNamesForType(type).length;
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Expected " + typeName + " on the bundle classpath", e);
        }
    }
}
