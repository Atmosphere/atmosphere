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

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Omnichannel AI agent — one {@code @Agent} class serves every surface:
 * <ul>
 *   <li>Web clients via Atmosphere (WebSocket/SSE/long-polling)</li>
 *   <li>Telegram, Slack, Discord, WhatsApp, Messenger</li>
 * </ul>
 *
 * <p>The persona, including the system prompt and the {@code ## Channels} the
 * agent serves, lives in the skill file {@code skill:omnichannel-chat}
 * (resolved from {@code META-INF/skills/omnichannel-chat/SKILL.md}). When
 * {@code atmosphere-channels} is on the classpath, the framework auto-wires
 * the agent's pipeline to every listed channel via {@code ChannelAiBridge} —
 * no per-channel delivery code in this sample.</p>
 */
@AgentScope(unrestricted = true,
        justification = "Omnichannel bridge demo; relays arbitrary chat from web and messaging channels by design")
@Agent(name = "omnichannel",
        skillFile = "skill:omnichannel-chat",
        description = "Omnichannel AI assistant — one agent on Web, Telegram, Slack, "
                + "Discord, WhatsApp, and Messenger")
public class OmnichannelChat {

    private static final Logger logger = LoggerFactory.getLogger(OmnichannelChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Web client connected: {}", resource.uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Prompt: {}", message);
        // Always through the pipeline: the no-key fallback runtime answers when no
        // LLM_API_KEY is set, the real runtime answers when it is. The same pipeline
        // serves every channel via ChannelAiBridge.
        session.stream(message);
    }
}
