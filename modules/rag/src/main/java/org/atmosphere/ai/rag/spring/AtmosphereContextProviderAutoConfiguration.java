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
package org.atmosphere.ai.rag.spring;

import java.util.List;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.rag.InMemoryContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that provides a safe default {@link ContextProvider} when
 * the {@code atmosphere-rag} JAR is on the classpath and the application supplies
 * none of its own.
 *
 * <p>Mirrors the permissive-default-with-warning idiom used elsewhere in the
 * framework (e.g. {@link org.atmosphere.spring.boot.DurableSessionAutoConfiguration}
 * for session stores): the bean materializes so the RAG family is a live,
 * injectable surface out of the box, while a startup {@code WARN} makes clear the
 * default retrieves nothing until a real retriever is wired.</p>
 *
 * <p>Runs {@code after} {@link AtmosphereRagAutoConfiguration} so that, when a
 * Spring AI {@code VectorStore} is present and bridged to a
 * {@code SpringAiVectorStoreContextProvider}, the
 * {@link ConditionalOnMissingBean} guard on {@link ContextProvider} sees that
 * provider and this default backs off entirely. Likewise any operator-supplied
 * {@code ContextProvider} {@code @Bean} wins — this default never overrides real
 * configuration.</p>
 */
@AutoConfiguration(after = AtmosphereRagAutoConfiguration.class)
@ConditionalOnClass(ContextProvider.class)
public class AtmosphereContextProviderAutoConfiguration {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereContextProviderAutoConfiguration.class);

    /**
     * Default in-memory context provider, used only when no other
     * {@link ContextProvider} bean is present. Holds no documents, so
     * {@code retrieve(...)} returns an empty result set — a safe no-op until a
     * real retriever is configured.
     *
     * @return an empty {@link InMemoryContextProvider}
     */
    @Bean
    @ConditionalOnMissingBean(ContextProvider.class)
    public ContextProvider contextProvider() {
        logger.warn("No ContextProvider configured — using empty in-memory RAG provider "
                + "(retrieves nothing); add a vector store (Spring AI VectorStore, pgvector, "
                + "Qdrant) or declare a ContextProvider @Bean for production retrieval.");
        return new InMemoryContextProvider(List.of());
    }
}
