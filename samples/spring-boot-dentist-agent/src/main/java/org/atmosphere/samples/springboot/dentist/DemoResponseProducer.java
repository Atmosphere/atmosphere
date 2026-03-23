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
package org.atmosphere.samples.springboot.dentist;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates Dr. Molar's responses for demo/testing when no LLM API key is set.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set LLM_API_KEY for real AI responses. Try /help for commands!");
            for (var word : words) {
                session.send(word);
                Thread.sleep(40);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();

        if (lower.contains("broke") || lower.contains("broken") || lower.contains("cracked")) {
            return "I'm sorry to hear about your broken tooth! That can be really "
                    + "stressful. First, don't panic — most broken teeth can be repaired. "
                    + "Rinse your mouth gently with warm water and apply a cold compress "
                    + "to reduce swelling. If you have the broken piece, save it in milk. "
                    + "Try /firstaid for detailed steps, or /urgency to check how soon "
                    + "you need to see a dentist. Remember, I'm an AI assistant — please "
                    + "see a real dentist as soon as possible!";
        }

        if (lower.contains("pain") || lower.contains("hurt") || lower.contains("ache")) {
            return "I understand you're in pain — dental pain can be really intense. "
                    + "For immediate relief, try ibuprofen (Advil) if you can take it, "
                    + "and apply a cold compress to the outside of your cheek. "
                    + "Type /pain for detailed pain management tips. "
                    + "On a scale of 1-10, how bad is your pain? This will help me "
                    + "guide you better.";
        }

        if (lower.contains("bleeding") || lower.contains("blood")) {
            return "Bleeding from a dental injury needs attention. Bite down gently "
                    + "on a piece of gauze or a moistened tea bag for 15-20 minutes. "
                    + "If the bleeding is heavy and won't stop after 20 minutes of "
                    + "pressure, please go to the emergency room. For light bleeding, "
                    + "it should subside — but still see a dentist soon.";
        }

        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm Dr. Molar, your emergency dental assistant. "
                    + "I can help with broken teeth, dental pain, and emergency guidance. "
                    + "Tell me what happened, or try /help to see what I can do. "
                    + "Remember, I'm an AI — always follow up with a real dentist!";
        }

        return "I'm Dr. Molar, your dental emergency assistant (running in demo mode). "
                + "I can help with broken, chipped, or cracked teeth. "
                + "Tell me what happened to your tooth, or try these commands:\n"
                + "- /firstaid — Quick first-aid steps\n"
                + "- /urgency — Check how urgently you need care\n"
                + "- /pain — Pain management tips\n"
                + "- /help — See all commands\n\n"
                + "Set LLM_API_KEY for full AI-powered responses!";
    }
}
