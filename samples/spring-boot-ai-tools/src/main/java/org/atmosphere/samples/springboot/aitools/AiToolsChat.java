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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint demonstrating Atmosphere's {@code @AiTool} pipeline.
 * Uses the {@code tools} attribute to register {@link AssistantTools} methods
 * as backend-portable tools.
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "skill:tool-assistant",
        conversationMemory = true,
        maxHistoryMessages = 30,
        tools = AssistantTools.class,
        interceptors = {CostMeteringInterceptor.class, LifecycleForwardingInterceptor.class})
@AgentScope(unrestricted = true,
        justification = "Tool-calling demo — accepts arbitrary prompts to exercise @AiTool dispatch across runtimes.")
public class AiToolsChat {

    private static final Logger logger = LoggerFactory.getLogger(AiToolsChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected (peers: {})",
                resource.uuid(),
                resource.getBroadcaster().getAtmosphereResources().size());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("Prompt from {}: {}", resource.uuid(), message);

        // With no API key there is no model to pick tools, so the demo-mode
        // router keyword-matches the prompt and executes the real tool through
        // ToolExecutionHelper.executeWithApproval — real ToolStart/ToolResult
        // frames and the real @RequiresApproval gate (reset_city_data parks
        // until the client approves or denies). With a key configured, the
        // active runtime's own tool-calling loop takes over instead.
        // Mirrors DemoAgentRuntime.isAvailable(): an explicitly-bound native
        // client (e.g. SpringAiAgentRuntime.setChatClient) serves without any
        // key, so its tool-calling loop owns dispatch exclusively too.
        var settings = AiConfig.get();
        if (!AgentRuntimeResolver.hasExplicitClientBinding()
                && (settings == null || !settings.hasReachableModel())) {
            DemoToolRouter.route(message, session);
        }

        // Always through the pipeline: DemoAgentRuntime streams the strategy
        // installed by DemoResponseProducer when no LLM_API_KEY is configured,
        // otherwise the real runtime streams — guardrails, interceptors,
        // memory, and metrics fire the same way on both paths.
        session.stream(message);
    }
}
