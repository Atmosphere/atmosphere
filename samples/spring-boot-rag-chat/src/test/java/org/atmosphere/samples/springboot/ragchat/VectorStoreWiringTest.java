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
package org.atmosphere.samples.springboot.ragchat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the 4.0.67 "rag-chat can never retrieve" defect.
 *
 * <p>{@link VectorRetrievalTest} already covers the retrieval <em>logic</em>, but it builds a
 * {@code SimpleVectorStore} by hand and never starts a Spring context — which is exactly why it
 * stayed green while the shipped application produced no {@code VectorStore} at all. This test
 * covers the missing half: the bean <em>wiring</em>.</p>
 *
 * <p>The ordering here is the whole point. {@code VectorStoreConfig} is registered as a
 * <strong>user configuration</strong> and the {@link EmbeddingModel} is contributed by an
 * <strong>auto-configuration</strong>, which is how the real application is assembled. Spring
 * parses user configurations first and auto-configurations last (they arrive through a
 * {@code DeferredImportSelector}), so a {@code @ConditionalOnBean(EmbeddingModel.class)} on the
 * {@code vectorStore} bean is evaluated while the registry still has no {@code EmbeddingModel}
 * and is therefore <em>always</em> false.</p>
 *
 * <p><strong>Proven to bite:</strong> against the pre-fix source this test fails with
 * "Expecting actual not to be null" on the {@code VectorStore} lookup, because
 * {@code @ConditionalOnBean} suppressed the bean. Registering the stub through
 * {@link AutoConfigurations} rather than {@code withBean(...)} is deliberate — {@code withBean}
 * registers the definition up front, which would make the stale condition match and quietly
 * defeat the test.</p>
 */
class VectorStoreWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StubEmbeddingAutoConfiguration.class))
            .withUserConfiguration(VectorStoreConfig.class);

    @Test
    void vectorStoreBeanIsCreatedWhenEnabledAndAnEmbeddingModelIsAutoConfigured() {
        runner.withPropertyValues("atmosphere.ai.vector-store.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStore.class);
                });
    }

    /**
     * Default-off is load-bearing, not a preference: an empty store becomes the retrieval
     * source and starves the keyless demo path of word-overlap context, which the
     * `rag-chat-commands` e2e pins via its "Relevant context" assertion. Enabling this by
     * default is exactly the regression that spec caught.
     */
    @Test
    void vectorStoreIsAbsentByDefaultSoTheKeylessPathKeepsItsContextProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(VectorStore.class);
        });
    }

    /**
     * Contributes the {@link EmbeddingModel} the way Spring AI does — from an
     * auto-configuration, evaluated after user configurations.
     */
    @AutoConfiguration
    static class StubEmbeddingAutoConfiguration {

        @Bean
        EmbeddingModel embeddingModel() {
            return new VectorRetrievalTest.TopicEmbeddingModel();
        }
    }
}
