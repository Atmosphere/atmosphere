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

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates Embabel agent responses for demo/testing purposes.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated agent response word-by-word through the session.
     */
    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set OPENAI_API_KEY to enable real agents");
            for (var word : words) {
                session.send(word);
                Thread.sleep(50);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm running in demo mode because no OPENAI_API_KEY is configured. "
                    + "This sample uses Embabel's AgentPlatform to run AI agents that stream "
                    + "responses through Atmosphere. Set OPENAI_API_KEY to use a real agent.";
        }
        if (lower.contains("embabel") || lower.contains("agent")) {
            return "Embabel is a Spring-based agent framework that provides AgentPlatform, "
                    + "typed agents, and tool calling. Atmosphere's EmbabelStreamingAdapter "
                    + "bridges agent output events into real-time WebSocket streams, "
                    + "delivering tokens to connected browsers as they're generated.";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "This sample demonstrates how Embabel agents integrate with Atmosphere's "
                    + "Broadcaster to push AI agent responses to all connected browser clients.";
        }
        return "I received your message: \"" + userMessage + "\". "
                + "This is a demo response — each word arrives as a separate streaming token. "
                + "Set OPENAI_API_KEY to connect to a real Embabel agent.";
    }
}
