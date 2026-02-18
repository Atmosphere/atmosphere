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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.embabel.EmbabelStreamingAdapter;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent runner that bridges LLM responses through Embabel's
 * {@link org.atmosphere.ai.embabel.AtmosphereOutputChannel}.
 *
 * <p>In a production application, the runner lambda would invoke the
 * Embabel agent platform:</p>
 * <pre>{@code
 * new AgentRequest("my-agent", channel ->
 *     agentPlatform.runProcess(agent, input, channel)
 * )
 * }</pre>
 *
 * <p>This sample demonstrates the OutputChannel streaming pattern using
 * the built-in OpenAI-compatible LLM client for simplicity.</p>
 */
public final class AgentRunner {

    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);
    private static final EmbabelStreamingAdapter ADAPTER = new EmbabelStreamingAdapter();

    private AgentRunner() {
    }

    /**
     * Run an AI agent for the given user prompt, streaming results
     * through the Atmosphere resource via Embabel's OutputChannel.
     */
    public static void run(String userMessage, AtmosphereResource resource) {
        var settings = AiConfig.get();
        var session = StreamingSessions.start(resource);

        var agentRequest = new EmbabelStreamingAdapter.AgentRequest("chat-agent", channel -> {
            try {
                var request = ChatCompletionRequest.builder(settings.model())
                        .system("You are a helpful AI agent. Think step by step and provide clear, concise answers.")
                        .user(userMessage)
                        .build();

                // Stream tokens via the session (in a real Embabel app, the agent
                // platform would send events through the OutputChannel)
                settings.client().streamChatCompletion(request, session);
            } catch (Exception e) {
                logger.error("Agent execution failed", e);
                session.error(e);
            }
            return kotlin.Unit.INSTANCE;
        });

        Thread.startVirtualThread(() -> ADAPTER.stream(agentRequest, session));
    }
}
