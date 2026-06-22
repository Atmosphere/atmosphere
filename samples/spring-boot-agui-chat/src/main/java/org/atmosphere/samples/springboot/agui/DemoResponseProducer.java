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
package org.atmosphere.samples.springboot.agui;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;

/**
 * Streams a deterministic demo response when no LLM key is configured, so the
 * sample produces a real AG-UI event sequence (TEXT_MESSAGE_START → N ×
 * TEXT_MESSAGE_CONTENT → TEXT_MESSAGE_END → RUN_FINISHED) out of the box.
 *
 * <p>Each call goes through the same {@link StreamingSession} the real pipeline
 * uses, so the AG-UI frames are produced by the shipped {@code AgUiEventMapper}
 * — not hand-written here. Set {@code LLM_API_KEY} to drive a real model instead
 * (which additionally exercises {@code @AiTool} dispatch).</p>
 */
public final class DemoResponseProducer {

    /** Stable phrase the demo always includes, asserted by the e2e demo lane. */
    static final String DEMO_PHRASE = "AG-UI protocol";

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated response word-by-word through the session, then emit a
     * terminal {@link AiEvent.TextComplete} so the mapper closes the message with
     * a {@code TEXT_MESSAGE_END} before {@link StreamingSession#complete()} writes
     * {@code RUN_FINISHED}.
     */
    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        // Keep trailing whitespace on each token so the concatenated deltas
        // reproduce the response verbatim (the e2e demo lane joins them).
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set LLM_API_KEY to enable real model responses");
            for (var word : words) {
                session.send(word);
                Thread.sleep(40);
            }
            // Close the in-flight AG-UI text message (TEXT_MESSAGE_END) before
            // the run finishes — mirrors the real pipeline's terminal TextComplete.
            session.emit(new AiEvent.TextComplete(response));
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("weather")) {
            return "I can fetch weather through the get_weather tool when a model is connected. "
                    + "Right now I'm streaming over the AG-UI protocol in demo mode — "
                    + "set LLM_API_KEY to let the model call the tool for real.";
        }
        if (lower.contains("time")) {
            return "I can look up the time through the get_time tool when a model is connected. "
                    + "Right now I'm streaming over the AG-UI protocol in demo mode — "
                    + "set LLM_API_KEY to let the model call the tool for real.";
        }
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm an Atmosphere agent streaming over the AG-UI protocol. "
                    + "I'm in demo mode because no LLM_API_KEY is set. Configure a key to get "
                    + "real model responses with get_weather and get_time tool calls.";
        }
        return "I received your message and streamed this reply over the AG-UI protocol. "
                + "This is demo mode (no LLM_API_KEY) — each word arrives as a separate "
                + "TEXT_MESSAGE_CONTENT frame. Set LLM_API_KEY for real model responses.";
    }
}
