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
package org.atmosphere.samples.springboot.langchain4jchat;

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
                    + "This sample uses LangChain4j to stream AI responses through Atmosphere. "
                    + "Set LLM_API_KEY to connect to a real provider.";
        }
        if (lower.contains("langchain") || lower.contains("lang chain")) {
            return "LangChain4j is a Java framework for building LLM-powered applications. "
                    + "Atmosphere's LangChain4jStreamingAdapter bridges LangChain4j's callback-based "
                    + "streaming into Atmosphere's real-time transport layer, delivering "
                    + "tokens to browsers via WebSocket, SSE, or gRPC.";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "This sample demonstrates how LangChain4j's StreamingChatLanguageModel "
                    + "integrates with Atmosphere's Broadcaster to push AI tokens to all "
                    + "connected browser clients in real-time.";
        }
        return "I received your message: \"" + userMessage + "\". "
                + "This is a demo response — each word arrives as a separate streaming token. "
                + "Set LLM_API_KEY to connect to a real LLM provider.";
    }
}
