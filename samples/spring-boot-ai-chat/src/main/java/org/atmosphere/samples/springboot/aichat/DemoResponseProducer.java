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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates LLM streaming responses for demo/testing purposes.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated response word-by-word through the session.
     */
    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set LLM_API_KEY to enable real responses");
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
            return "Hello! I'm running in demo mode because no LLM_API_KEY is configured. "
                    + "Set LLM_API_KEY to connect to Gemini, OpenAI, or any compatible provider. "
                    + "For local models, set LLM_MODE=local and run Ollama.";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "It supports WebSocket, SSE, long-polling, and gRPC transports. "
                    + "This sample uses the built-in OpenAiCompatibleClient to stream "
                    + "LLM responses token-by-token through the Broadcaster.";
        }
        return "I received your message: \"" + userMessage + "\". "
                + "This is a demo response — each word arrives as a separate streaming token. "
                + "Set LLM_API_KEY to connect to a real LLM provider.";
    }
}
