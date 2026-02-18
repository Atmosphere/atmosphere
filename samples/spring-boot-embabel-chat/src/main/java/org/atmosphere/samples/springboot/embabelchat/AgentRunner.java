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
package org.atmosphere.samples.springboot.embabelchat;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.ProcessOptions;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.embabel.AtmosphereOutputChannel;
import org.atmosphere.ai.embabel.EmbabelStreamingAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs Embabel agents and streams their output to browsers via Atmosphere.
 *
 * <p>Receives user prompts from the {@link EmbabelChat} endpoint, looks up
 * the deployed {@code chat-assistant} agent, and executes it through the
 * Embabel {@link AgentPlatform}. Agent events (tokens, progress, logs) flow
 * through the {@link AtmosphereOutputChannel} to the connected browser in
 * real time.</p>
 */
@Component
public class AgentRunner {

    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);
    private static final EmbabelStreamingAdapter ADAPTER = new EmbabelStreamingAdapter();

    private final AgentPlatform agentPlatform;

    public AgentRunner(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    /**
     * Run the {@code chat-assistant} agent for the given prompt, streaming
     * results to the browser via Atmosphere's WebSocket transport.
     */
    public void run(String userMessage, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        var agent = agentPlatform.agents().stream()
                .filter(a -> "chat-assistant".equals(a.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Agent 'chat-assistant' not deployed on the platform"));

        var agentRequest = new EmbabelStreamingAdapter.AgentRequest("chat-assistant", channel -> {
            try {
                var options = ProcessOptions.DEFAULT
                        .withOutputChannel(channel);

                agentPlatform.runAgentFrom(agent, options,
                        Map.of("userMessage", userMessage));
            } catch (Exception e) {
                logger.error("Agent execution failed", e);
                session.error(e);
            }
            return kotlin.Unit.INSTANCE;
        });

        Thread.startVirtualThread(() -> ADAPTER.stream(agentRequest, session));
    }
}
