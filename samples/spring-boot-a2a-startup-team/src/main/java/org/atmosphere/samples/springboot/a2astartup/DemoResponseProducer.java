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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.ai.StreamingSession;

/**
 * Demo mode fallback when no Gemini API key is configured.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    public static void stream(String userMessage, StreamingSession session) {
        var response = """
                ## Welcome to the A2A Startup Team!

                This sample demonstrates **true multi-agent collaboration** using the A2A protocol.

                Each specialist is an independent headless `@Agent` with its own Agent Card:
                - **Research Agent** at `/atmosphere/a2a/research` — web scraping via JSoup
                - **Strategy Agent** at `/atmosphere/a2a/strategy` — SWOT analysis
                - **Finance Agent** at `/atmosphere/a2a/finance` — TAM/SAM/SOM projections
                - **Writer Agent** at `/atmosphere/a2a/writer` — executive briefings

                The CEO coordinator discovers them via Agent Cards and delegates via JSON-RPC.

                **Set GEMINI_API_KEY to see real A2A multi-agent collaboration!**

                Try: "Analyze the market for AI developer tools in 2026"
                """;
        var words = response.split("(?<=\\s)");
        try {
            session.progress("Demo mode — set GEMINI_API_KEY for real A2A collaboration!");
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
}
