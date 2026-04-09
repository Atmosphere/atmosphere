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
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AgentRuntimeResolver;
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

    AtmosphereAiEndpointRegistrar(AtmosphereFramework framework,
                                  AtmosphereProperties properties) {
        framework.getAtmosphereConfig().startupHook(f -> {
            if (hasUserDefinedAiEndpoint(f)) {
                logger.info("User-defined @AiEndpoint detected, skipping default AI endpoint registration");
                return;
            }
            registerDefaultEndpoint(f, properties);
        });
    }

    private boolean hasUserDefinedAiEndpoint(AtmosphereFramework framework) {
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
                new DefaultToolRegistry(), List.of(), List.of(),
                AiMetrics.NOOP, List.of(), null);

        List<AtmosphereInterceptor> interceptors = new LinkedList<>();
        AnnotationUtil.defaultManagedServiceInterceptors(framework, interceptors);
        framework.addAtmosphereHandler(path, handler, interceptors);

        logger.info("Default AI chat endpoint registered at {}", path);
    }
}
