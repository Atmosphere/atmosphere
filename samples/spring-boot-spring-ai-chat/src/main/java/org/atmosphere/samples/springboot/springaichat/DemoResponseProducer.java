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
package org.atmosphere.samples.springboot.springaichat;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates Spring AI streaming responses for demo/testing purposes.
 * Used when no OPENAI_API_KEY is configured so the sample works out-of-the-box.
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
            session.progress("Demo mode — set OPENAI_API_KEY to enable Spring AI responses");
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
                    + "This sample uses Spring AI's ChatClient with Atmosphere's SpringAiStreamingAdapter "
                    + "to stream LLM responses in real-time over WebSocket.";
        }
        if (lower.contains("spring ai") || lower.contains("spring")) {
            return "Spring AI provides a unified ChatClient API for interacting with LLMs. "
                    + "This sample uses atmosphere-spring-ai to bridge Spring AI's Flux-based streaming "
                    + "into Atmosphere's StreamingSession — giving you real-time token-by-token push "
                    + "over WebSocket, SSE, or any Atmosphere transport.";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for real-time web applications. "
                    + "Combined with Spring AI, you get the best of both worlds: "
                    + "Spring AI's rich LLM abstraction (ChatClient, Advisors, RAG) "
                    + "plus Atmosphere's transport-agnostic real-time delivery.";
        }
        return "I received your message: \"" + userMessage + "\". "
                + "This is a demo response streamed word-by-word via SpringAiStreamingAdapter. "
                + "Set OPENAI_API_KEY to connect to a real LLM provider through Spring AI's ChatClient.";
    }
}
