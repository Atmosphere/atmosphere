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
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.interactions.InMemoryInteractionStore;
import org.atmosphere.interactions.InteractionService;
import org.atmosphere.interactions.InteractionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Interactions API HTTP surface. Wires a default
 * in-memory {@link InteractionStore}, an {@link InteractionService} over the
 * resolved {@code AgentRuntime}, and the {@link InteractionsEndpoint} REST
 * controller.
 *
 * <p>Ordered after {@link AtmosphereAiAutoConfiguration} so the runtime is
 * resolved against the configured AI settings. Every bean is
 * {@link ConditionalOnMissingBean} so an application can supply its own store
 * (e.g. {@code SqliteInteractionStore}), a chaining-capable
 * {@link AiConversationMemory}, or a fully customized service. Disable the
 * whole surface with {@code atmosphere.interactions.enabled=false}.</p>
 */
@AutoConfiguration(after = AtmosphereAiAutoConfiguration.class)
@ConditionalOnClass({InteractionService.class, AiConfig.class})
@ConditionalOnProperty(name = "atmosphere.interactions.enabled", matchIfMissing = true)
public class InteractionsAutoConfiguration {

    /** Default in-memory store; Spring stops it on context close (Ownership). */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(InteractionStore.class)
    public InteractionStore atmosphereInteractionStore() {
        var store = new InMemoryInteractionStore();
        store.start();
        return store;
    }

    /**
     * The Interactions facade over the resolved runtime. Spring stops it on
     * context close so the service-owned background executor is released.
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(InteractionService.class)
    public InteractionService atmosphereInteractionService(
            InteractionStore store,
            ObjectProvider<AiConversationMemory> memoryProvider) {
        var runtime = AgentRuntimeResolver.resolve();
        var service = new InteractionService(runtime, store, memoryProvider.getIfAvailable());
        service.start();
        return service;
    }
}
