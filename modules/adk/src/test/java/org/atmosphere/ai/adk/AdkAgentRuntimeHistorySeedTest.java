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
package org.atmosphere.ai.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link AdkAgentRuntime#seedSessionFromHistory} threads
 * Atmosphere-persisted conversation history into ADK's Session via
 * {@code BaseSessionService.appendEvent}. Closes the Correctness Invariant #5
 * gap where {@code AiCapability.CONVERSATION_MEMORY} was advertised but
 * {@code context.history()} was silently dropped on first turn of a new
 * session.
 */
class AdkAgentRuntimeHistorySeedTest {

    @Test
    void seedsAdkSessionWithRoleMappedHistory() {
        var agent = LlmAgent.builder()
                .name("test-agent")
                .description("history seed test")
                .model("gemini-2.5-flash")
                .build();
        var runner = new InMemoryRunner(agent, "history-seed-test");
        var userId = "ChefFamille";
        var sessionId = "sess-" + System.nanoTime();

        // Create an empty session — the real call path uses ensureSession but
        // we mirror its net effect here to keep the test focused on seeding.
        runner.sessionService()
                .createSession(runner.appName(), userId, java.util.Map.of(), sessionId)
                .blockingGet();

        AdkAgentRuntime.seedSessionFromHistory(runner, userId, sessionId, List.of(
                ChatMessage.user("What was my last training week like?"),
                ChatMessage.assistant("You did three interval sessions and one long run."),
                ChatMessage.system("Only refer to data verified by the user's providers."),
                ChatMessage.tool("strava:weekly=42km", "call-123")
        ));

        var session = runner.sessionService()
                .getSession(runner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        var events = session.events();

        assertEquals(4, events.size(), "all four history messages should land as ADK events");
        assertEquals("user", events.get(0).author());
        assertEquals("model", events.get(1).author(),
                "assistant messages map to ADK's 'model' author");
        assertEquals("system", events.get(2).author());
        assertEquals("user", events.get(3).author(),
                "tool-role messages surface as user-origin events in ADK");

        assertEquals("What was my last training week like?",
                textOf(events.get(0)));
        assertEquals("You did three interval sessions and one long run.",
                textOf(events.get(1)));
        assertEquals("Only refer to data verified by the user's providers.",
                textOf(events.get(2)));
        assertEquals("strava:weekly=42km", textOf(events.get(3)));
    }

    @Test
    void emptyHistoryIsNoOp() {
        var agent = LlmAgent.builder()
                .name("test-agent-empty")
                .description("empty history seed")
                .model("gemini-2.5-flash")
                .build();
        var runner = new InMemoryRunner(agent, "history-seed-empty");
        var sessionId = "sess-" + System.nanoTime();

        runner.sessionService()
                .createSession(runner.appName(), "u1", java.util.Map.of(), sessionId)
                .blockingGet();

        AdkAgentRuntime.seedSessionFromHistory(runner, "u1", sessionId, List.of());

        var session = runner.sessionService()
                .getSession(runner.appName(), "u1", sessionId, Optional.empty())
                .blockingGet();
        assertEquals(0, session.events().size(), "empty history should not create any events");
    }

    private static String textOf(com.google.adk.events.Event event) {
        return event.content()
                .flatMap(c -> c.parts().stream()
                        .flatMap(java.util.Collection::stream)
                        .findFirst()
                        .flatMap(com.google.genai.types.Part::text))
                .orElse("");
    }
}
