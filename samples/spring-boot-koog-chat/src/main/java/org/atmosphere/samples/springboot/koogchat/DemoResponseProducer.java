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
package org.atmosphere.samples.springboot.koogchat;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates LLM streaming responses for demo/testing purposes.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode (Koog) — set LLM_API_KEY to enable real responses");
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
            return "Hello! I'm running in demo mode with JetBrains Koog as the AI runtime. "
                    + "No LLM_API_KEY is configured. Set LLM_API_KEY to connect to OpenAI, "
                    + "Gemini, or any provider supported by Koog.";
        }
        if (lower.contains("atmosphere") || lower.contains("koog")) {
            return "This sample uses Atmosphere with JetBrains Koog as the AI runtime. "
                    + "Koog provides streaming via PromptExecutor, tool calling via @Tool, "
                    + "and agent orchestration via graph/functional strategies. "
                    + "Atmosphere handles the real-time WebSocket transport to your browser.";
        }
        return "This is a demo response from the Koog sample — each word streams in real-time. "
                + "Try asking about 'atmosphere' or 'koog'. "
                + "Set LLM_API_KEY to use a real LLM provider.";
    }
}
