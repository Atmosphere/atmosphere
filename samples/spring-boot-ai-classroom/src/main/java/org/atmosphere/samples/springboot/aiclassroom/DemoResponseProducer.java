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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates LLM streaming responses with room-aware personas.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated response word-by-word through the session.
     */
    public static void stream(String userMessage, StreamingSession session, String room) {
        var response = generateResponse(userMessage, room);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode (room: " + room
                    + ") — set LLM_API_KEY to enable real AI responses");
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

    private static String generateResponse(String userMessage, String room) {
        return switch (room) {
            case "math" -> "[Math Tutor] Great question! Regarding \"" + userMessage
                    + "\": Mathematics is all about patterns and logical reasoning. "
                    + "In demo mode, I can't compute real answers, but with an API key "
                    + "I'll solve equations step by step. "
                    + "Set LLM_API_KEY to connect to a real AI model.";
            case "code" -> "[Code Mentor] Let me help with \"" + userMessage
                    + "\": Good software is built on clear abstractions and clean design. "
                    + "In demo mode, I can't write real code, but with an API key "
                    + "I'll provide working examples with explanations. "
                    + "Set LLM_API_KEY to connect to a real AI model.";
            case "science" -> "[Science Educator] Interesting question about \"" + userMessage
                    + "\": Science is the art of asking why and testing how. "
                    + "In demo mode, I can't give detailed explanations, but with an API key "
                    + "I'll break down complex topics with analogies. "
                    + "Set LLM_API_KEY to connect to a real AI model.";
            default -> "[Classroom AI] You asked: \"" + userMessage + "\". "
                    + "Every student in this room is seeing this response stream "
                    + "in real time — that's Atmosphere's broadcaster at work! "
                    + "Set LLM_API_KEY to connect to a real AI model.";
        };
    }
}
