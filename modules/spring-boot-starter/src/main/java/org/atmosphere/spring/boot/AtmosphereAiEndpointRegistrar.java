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
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.annotation.AnnotationUtil;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

/**
 * Registers a default AI chat endpoint when no user-defined {@code @AiEndpoint}
 * is detected. Uses {@link AtmosphereFramework}'s startup hook to run after
 * annotation scanning is complete.
 */
class AtmosphereAiEndpointRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAiEndpointRegistrar.class);

    private final List<AiGuardrail> guardrails;

    AtmosphereAiEndpointRegistrar(AtmosphereFramework framework,
                                  AtmosphereProperties properties,
                                  List<AiGuardrail> guardrails) {
        // Merge Spring-bridged beans with ServiceLoader providers so
        // plain-servlet / Quarkus-native classpaths pick up guardrails too —
        // same discovery shape as AsyncSupport / CoordinationJournal. Spring
        // beans win on duplicate class names.
        var merged = new java.util.LinkedHashMap<Class<?>, AiGuardrail>();
        if (guardrails != null) {
            for (var g : guardrails) {
                if (g != null) {
                    merged.putIfAbsent(g.getClass(), g);
                }
            }
        }
        try {
            for (var g : java.util.ServiceLoader.load(AiGuardrail.class)) {
                if (g != null) {
                    merged.putIfAbsent(g.getClass(), g);
                }
            }
        } catch (java.util.ServiceConfigurationError e) {
            logger.warn("AiGuardrail ServiceLoader lookup failed: {}", e.getMessage());
        }
        this.guardrails = List.copyOf(merged.values());
        framework.getAtmosphereConfig().startupHook(f -> {
            if (hasUserDefinedAiEndpoint(f, properties)) {
                logger.info("User-defined @AiEndpoint detected, skipping default AI endpoint registration");
                return;
            }
            registerDefaultEndpoint(f, properties);
        });
    }

    private boolean hasUserDefinedAiEndpoint(AtmosphereFramework framework,
                                              AtmosphereProperties properties) {
        for (var entry : framework.getAtmosphereHandlers().values()) {
            var handler = entry.atmosphereHandler();
            if (handler instanceof AiEndpointHandler) {
                return true;
            }
            // @Agent-annotated classes register an AgentHandler that wraps AiEndpointHandler
            if (handler.getClass().getName().equals("org.atmosphere.agent.processor.AgentHandler")) {
                return true;
            }
        }
        // Another handler (e.g. @ManagedService) already occupies the target path
        var path = properties.getAi().getPath();
        if (framework.getAtmosphereHandlers().containsKey(path)) {
            return true;
        }
        return false;
    }

    private void registerDefaultEndpoint(AtmosphereFramework framework,
                                         AtmosphereProperties properties) {
        var aiProps = properties.getAi();
        var path = aiProps.getPath();

        // Resolve system prompt
        var systemPrompt = aiProps.getSystemPrompt();
        if (aiProps.getSystemPromptResource() != null && !aiProps.getSystemPromptResource().isEmpty()) {
            systemPrompt = PromptLoader.resolve(aiProps.getSystemPromptResource());
        }

        // Resolve AI support
        var settings = AiConfig.get();
        var runtime = AgentRuntimeResolver.resolve();
        if (settings != null) {
            runtime.configure(settings);
        }

        // Conversation memory
        AiConversationMemory memory = null;
        if (aiProps.isConversationMemory()) {
            memory = new InMemoryConversationMemory(aiProps.getMaxHistoryMessages());
        }

        // Create the default endpoint
        var target = new DefaultAiChatEndpoint();
        var promptMethod = target.getClass().getDeclaredMethods()[0]; // onPrompt
        var lifecycle = AnnotatedLifecycle.scan(target.getClass());

        var handler = new AiEndpointHandler(
                target, promptMethod, aiProps.getTimeout(),
                systemPrompt, path, runtime, List.of(),
                memory, lifecycle,
                new DefaultToolRegistry(), guardrails, List.of(),
                AiMetrics.NOOP, List.of(), null);

        List<AtmosphereInterceptor> interceptors = new LinkedList<>();
        AnnotationUtil.defaultManagedServiceInterceptors(framework, interceptors);
        framework.addAtmosphereHandler(path, handler, interceptors);

        if (!guardrails.isEmpty()) {
            logger.info("Default AI chat endpoint registered at {} with {} guardrail(s): {}",
                    path, guardrails.size(),
                    guardrails.stream().map(g -> g.getClass().getSimpleName()).toList());
        } else {
            logger.info("Default AI chat endpoint registered at {}", path);
        }
    }
}
