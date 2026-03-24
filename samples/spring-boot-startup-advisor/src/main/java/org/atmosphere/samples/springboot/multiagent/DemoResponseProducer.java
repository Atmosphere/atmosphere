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
package org.atmosphere.samples.springboot.multiagent;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates a multi-agent startup team response for demo mode.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated multi-agent response word-by-word.
     */
    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set GEMINI_API_KEY for real multi-agent collaboration!");
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
        if (lower.contains("ai") || lower.contains("developer") || lower.contains("tool")) {
            return AI_TOOLS_DEMO;
        }
        return DEFAULT_DEMO;
    }

    private static final String AI_TOOLS_DEMO = """
            ## AI Developer Tools — Market Analysis

            My team has completed the analysis. Here's what we found:

            ### Research Agent
            The AI developer tools market reached **$15B in 2026**, growing 45% YoY.
            Key players: GitHub Copilot, Cursor, Windsurf, Claude Code.
            78% of developers now use AI tools daily (Stack Overflow 2026).

            ### Strategy Agent
            **Opportunity**: IDE-agnostic AI assistant with deep codebase understanding.
            **Threat**: GitHub Copilot dominance (40% market share).
            **Positioning**: Focus on enterprise teams needing privacy + customization.

            ### Finance Agent
            | Metric | Value |
            |--------|-------|
            | TAM | $15B |
            | SAM | $3B |
            | SOM | $150M |
            | Year 1 Revenue | $1.5M |
            | Seed Raise | $2M at $8M pre |
            | Break-even | Month 15 |

            ### CEO Recommendation
            **GO** — High confidence. The market is large, growing, and still fragmented \
            enough for a differentiated entrant. Key: ship fast, focus on enterprise.

            *Set GEMINI_API_KEY to see real multi-agent collaboration with live web search!*
            """;

    private static final String DEFAULT_DEMO = """
            ## Welcome to the AI Startup Team!

            I'm the CEO, and I coordinate a team of specialist AI agents:

            - **Research Agent** — scrapes the web for market data and trends
            - **Strategy Agent** — analyzes opportunities, threats, and positioning
            - **Finance Agent** — builds financial models and projections
            - **Writer Agent** — synthesizes everything into executive briefings

            **Try asking:**
            > "Analyze the market for AI-powered developer tools in 2026"
            > "What's the opportunity in AI agents for enterprise?"
            > "Evaluate the market for real-time collaboration tools"

            Each agent uses a different AI backend (LangChain4j, Spring AI, Google ADK, \
            Embabel, built-in) — demonstrating Atmosphere's framework-agnostic architecture.

            *Set GEMINI_API_KEY for real multi-agent collaboration powered by Gemini!*
            """;
}
