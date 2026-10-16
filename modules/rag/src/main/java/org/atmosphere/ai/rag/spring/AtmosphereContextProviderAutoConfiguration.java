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
import java.util.concurrent.atomic.AtomicReference;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.processor.AiEndpointProcessor;
import org.atmosphere.ai.rag.InMemoryContextProvider;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
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
 * <p>The warning is decided once the application is ready, not when the bean is
 * created: an {@code @AiEndpoint(contextProviders = ...)} instantiates its own
 * providers during framework initialization, without any bean, and then
 * retrieval does apply. The warning fires only when this empty default is the
 * application's {@code ContextProvider} bean <em>and</em> no endpoint wired a
 * provider ({@link AiEndpointProcessor#WIRED_CONTEXT_PROVIDERS_PROPERTY}).</p>
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

    static final String NO_PROVIDER_WARNING = "No ContextProvider configured — using empty "
            + "in-memory RAG provider (retrieves nothing); add a vector store (Spring AI "
            + "VectorStore, pgvector, Qdrant), declare a ContextProvider @Bean, or declare "
            + "@AiEndpoint(contextProviders = ...) for retrieval.";

    /** The empty default this configuration created, when it created one. */
    private final AtomicReference<ContextProvider> createdDefault = new AtomicReference<>();

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
        var provider = new InMemoryContextProvider(List.of());
        createdDefault.set(provider);
        return provider;
    }

    /**
     * Reports, once the application is ready, whether the empty default is all
     * the retrieval there is.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> atmosphereContextProviderReport(
            ObjectProvider<AtmosphereFramework> framework) {
        return event -> report(framework.getIfAvailable());
    }

    /** Logs the warning when {@link #shouldWarn} says so; package-private for tests. */
    void report(AtmosphereFramework framework) {
        var wired = wiredEndpointProviders(framework);
        if (shouldWarn(createdDefault.get() != null, wired)) {
            logger.warn(NO_PROVIDER_WARNING);
        } else if (createdDefault.get() != null) {
            logger.debug("Empty default ContextProvider bean in place; {} provider(s) wired "
                    + "by @AiEndpoint declarations retrieve for their endpoints", wired);
        }
    }

    /**
     * Whether the "no ContextProvider" warning is true: this configuration
     * created the empty default (no application bean exists) and no endpoint
     * wired a provider of its own.
     */
    static boolean shouldWarn(boolean defaultCreated, int wiredEndpointProviders) {
        return defaultCreated && wiredEndpointProviders <= 0;
    }

    private static int wiredEndpointProviders(AtmosphereFramework framework) {
        if (framework == null || framework.getAtmosphereConfig() == null) {
            return 0;
        }
        return framework.getAtmosphereConfig().properties()
                .get(AiEndpointProcessor.WIRED_CONTEXT_PROVIDERS_PROPERTY) instanceof Integer n ? n : 0;
    }
}
