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
package org.atmosphere.samples.channels;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Omnichannel AI chat — same endpoint serves:
 * <ul>
 *   <li>Web clients via Atmosphere (WebSocket/SSE/long-polling)</li>
 *   <li>Telegram, Slack, Discord, WhatsApp, Messenger via {@link ChannelBridge}</li>
 * </ul>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPrompt = "You are a helpful AI assistant. Keep responses concise and friendly.",
        conversationMemory = true)
@AgentScope(unrestricted = true,
        justification = "Omnichannel demo — accepts arbitrary prompts to showcase channel-bridge delivery across Slack / Telegram / WhatsApp / Discord / Messenger. Production deployments scope per channel.")
public class OmnichannelChat {

    private static final Logger logger = LoggerFactory.getLogger(OmnichannelChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Web client connected: {}", resource.uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Prompt: {}", message);
        // Always through the pipeline: DemoAgentRuntime answers when no
        // LLM_API_KEY is set, the real runtime answers when it is.
        session.stream(message);
    }
}
