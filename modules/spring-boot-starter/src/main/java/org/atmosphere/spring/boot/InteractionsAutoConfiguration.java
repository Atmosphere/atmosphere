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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionsAutoConfiguration.class);

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
        // Resolve the configured default model lazily (at request time, not bean
        // creation) so a Console request that omits a model still reaches the
        // runtime with one even when AiConfig is configured by a user @Bean whose
        // creation is not ordered relative to this service bean.
        java.util.function.Supplier<String> defaultModel = () -> {
            var settings = AiConfig.get();
            return settings != null ? settings.model() : null;
        };
        var service = new InteractionService(runtime, store, memoryProvider.getIfAvailable(),
                new org.atmosphere.interactions.InteractionStepMapper(),
                InteractionService.DEFAULT_MAX_STEPS, InteractionService.DEFAULT_SYNC_TIMEOUT,
                java.time.Clock.systemUTC(), null, null, defaultModel);
        service.start();
        return service;
    }

    /**
     * Demo-only authentication shim for local sample runs. When
     * {@code atmosphere.interactions.demo-principal} is set, a servlet filter
     * stamps that value as the {@code ai.userId} request attribute on
     * {@code /api/interactions/*} requests, so the Console can exercise the
     * mutating endpoints without a full auth provider.
     *
     * <p>Unset by default — the {@link InteractionsEndpoint} write gate then
     * stays default-deny (Correctness Invariant #6). This is an <em>explicit
     * opt-in</em> with a loud startup warning; it injects a fixed principal for
     * every caller and MUST NOT be enabled in production. Real deployments rely
     * on the servlet container's {@code getUserPrincipal()} or
     * {@code org.atmosphere.auth.principal} instead.</p>
     */
    @Bean
    @ConditionalOnProperty(name = "atmosphere.interactions.demo-principal")
    public FilterRegistrationBean<Filter> atmosphereInteractionsDemoPrincipal(
            org.springframework.core.env.Environment env) {
        var principal = env.getProperty("atmosphere.interactions.demo-principal", "");
        LOGGER.warn("atmosphere.interactions.demo-principal is set to '{}' — every "
                + "/api/interactions caller is treated as this user. DEMO ONLY; never "
                + "enable in production.", principal);
        Filter filter = (request, response, chain) -> {
            if (request instanceof HttpServletRequest http
                    && http.getRequestURI() != null
                    && http.getRequestURI().startsWith("/api/interactions")
                    && http.getAttribute("ai.userId") == null) {
                http.setAttribute("ai.userId", principal);
            }
            chain.doFilter(request, response);
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/interactions/*", "/api/interactions");
        registration.setName("atmosphereInteractionsDemoPrincipal");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
