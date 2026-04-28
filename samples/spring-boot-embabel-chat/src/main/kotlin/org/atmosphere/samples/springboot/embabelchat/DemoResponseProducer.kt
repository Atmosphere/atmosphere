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
package org.atmosphere.samples.springboot.embabelchat

import org.atmosphere.ai.StreamingSession

/**
 * Simulates LLM streaming responses for demo/testing purposes.
 * Used when no API key is configured so the sample boots out-of-the-box.
 */
object DemoResponseProducer {

    fun stream(userMessage: String, session: StreamingSession) {
        val response = generateResponse(userMessage)
        val words = response.split(Regex("(?<=\\s)"))

        try {
            session.progress("Demo mode (Embabel) — set LLM_API_KEY to enable real GOAP planning")
            for (word in words) {
                session.send(word)
                Thread.sleep(50)
            }
            session.complete(response)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            session.error(e)
        }
    }

    private fun generateResponse(userMessage: String): String {
        val lower = userMessage.lowercase()
        return when {
            "hello" in lower || "hi" in lower ->
                "Hello! I'm running in demo mode with Embabel as the AI runtime. " +
                    "No LLM_API_KEY is configured. Set LLM_API_KEY to engage Embabel's " +
                    "AgentPlatform with real GOAP-based planning."
            "atmosphere" in lower || "embabel" in lower ->
                "This sample uses Atmosphere with Embabel as the AI runtime. " +
                    "Embabel provides GOAP (Goal-Oriented Action Planning) over LLMs: " +
                    "the AgentPlatform plans a sequence of @AgentAction calls to satisfy " +
                    "your goal, then streams the result. Atmosphere handles real-time " +
                    "WebSocket delivery to the browser."
            else ->
                "This is a demo response from the Embabel sample — each word streams in real-time. " +
                    "Try asking about 'atmosphere' or 'embabel'. Set LLM_API_KEY to engage the " +
                    "real Embabel AgentPlatform."
        }
    }
}
