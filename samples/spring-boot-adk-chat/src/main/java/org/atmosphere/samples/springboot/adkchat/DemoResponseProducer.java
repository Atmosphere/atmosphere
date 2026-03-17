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
package org.atmosphere.samples.springboot.adkchat;

import org.atmosphere.ai.StreamingSession;

/**
 * Streams demo responses directly via {@link StreamingSession#send} for
 * reliable delivery in demo mode (no API key). Generates the same response
 * text as {@link DemoEventProducer} but bypasses the ADK event bridge.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — ADK agent (no API key set)");
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
            return "Hello! I'm an ADK agent running on Atmosphere Framework. "
                    + "I can stream responses in real-time to your browser via WebSocket, SSE, or gRPC. "
                    + "What would you like to know?";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "It supports WebSocket, SSE, long-polling, and gRPC transports. "
                    + "With the ADK integration, you can now stream Google ADK agent responses "
                    + "directly to connected browser clients using the Broadcaster pub/sub model.";
        }
        if (lower.contains("adk") || lower.contains("agent")) {
            return "Google ADK (Agent Development Kit) is an open-source toolkit for building "
                    + "AI agents with fine-grained control. It uses LlmAgent, Runner, and Flowable<Event> "
                    + "for streaming. The atmosphere-adk module bridges these event streams to "
                    + "Atmosphere's Broadcaster, so streaming texts reach browsers in real-time.";
        }
        return "This is a demo ADK agent streaming texts through Atmosphere's "
                + "real-time infrastructure. Each word you see arrives as a separate "
                + "streaming text via WebSocket. Try asking about 'atmosphere' or 'adk'!";
    }
}
