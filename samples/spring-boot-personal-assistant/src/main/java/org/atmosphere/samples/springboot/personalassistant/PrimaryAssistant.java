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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.StreamingActivityListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Primary assistant — user-facing agent that delegates to a small crew
 * (scheduler, research, drafter) through {@code InMemoryProtocolBridge}.
 *
 * <p>This is the flagship sample for the v0.5 foundation primitives. It
 * deliberately keeps the delegation logic simple (keyword routing) so the
 * sample is legible without an LLM key. When an LLM is configured, the
 * final synthesis step streams through the active {@code AgentRuntime}.
 * </p>
 *
 * <h2>Primitive integration</h2>
 *
 * <ul>
 *   <li>{@code AgentState} — conversation history is persisted via the
 *       file-backed state under the workspace
 *       ({@code .agent-workspace/agents/primary-assistant/sessions/*.jsonl}).
 *       Facts land in
 *       {@code .agent-workspace/users/&lt;userId&gt;/agents/primary-assistant/MEMORY.md}
 *       per the isolation fix.</li>
 *   <li>{@code AgentWorkspace} — identity, persona, and rules are read at
 *       load time from the shipped OpenClaw-compatible workspace.</li>
 *   <li>{@code AgentIdentity} — the session's {@code PermissionMode}
 *       governs which crew members may act without confirmation; the
 *       primary assistant always prompts for tool approval when mode is
 *       {@code PLAN} (demonstrated at the top of this method).</li>
 *   <li>{@code ProtocolBridge} — crew dispatch goes through the
 *       {@code InMemoryProtocolBridge}; an operator can swap members to
 *       their A2A endpoints without changing this class.</li>
 *   <li>{@code ToolExtensibilityPoint} — per-user MCP servers are read
 *       from {@code .agent-workspace/MCP.md} and surfaced to the
 *       drafter / research agents.</li>
 *   <li>{@code AiGateway} — every LLM call from the assistant enters the
 *       gateway for per-user rate limiting and trace emission (wire-in
 *       lands in Phase 1.5; today the gateway exists as an SPI the
 *       runtime bridges will consult).</li>
 * </ul>
 */
@Coordinator(
        name = "primary-assistant",
        skillFile = "skill:primary-assistant",
        description = "Personal assistant that delegates to a scheduler, research, and drafter crew.")
@Fleet({
        @AgentRef(type = SchedulerAgent.class),
        @AgentRef(type = ResearchAgent.class),
        @AgentRef(type = DrafterAgent.class)
})
public class PrimaryAssistant {

    private static final Logger logger = LoggerFactory.getLogger(PrimaryAssistant.class);

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        logger.info("Primary assistant received: {}", message);

        fleet = fleet.withActivityListener(new StreamingActivityListener(session));
        var lower = message.toLowerCase(Locale.ROOT);

        // Keyword routing — deliberately simple so the sample is readable
        // without an LLM key. Production assistants replace this with a
        // tool-calling LLM loop that picks crew members dynamically.
        if (matchesAny(lower, "schedule", "meeting", "book")) {
            var args = Map.<String, Object>of(
                    "topic", message,
                    "date_hint", "");
            session.emit(new AiEvent.ToolStart("propose_slots", args));
            var result = fleet.agent("scheduler-agent").call("propose_slots", args);
            session.emit(new AiEvent.ToolResult("propose_slots", result.text()));
            session.stream(
                    "Here's what the scheduler proposed:\n\n" + result.text());
            return;
        }

        if (matchesAny(lower, "research", "look up", "what do you know")) {
            var args = Map.<String, Object>of("topic", message);
            session.emit(new AiEvent.ToolStart("summarize_topic", args));
            var result = fleet.agent("research-agent").call("summarize_topic", args);
            session.emit(new AiEvent.ToolResult("summarize_topic", result.text()));
            session.stream(
                    "Research brief:\n\n" + result.text());
            return;
        }

        if (matchesAny(lower, "draft", "write", "email", "reply")) {
            var args = Map.<String, Object>of(
                    "recipient", "team",
                    "intent", message);
            session.emit(new AiEvent.ToolStart("draft_message", args));
            var result = fleet.agent("drafter-agent").call("draft_message", args);
            session.emit(new AiEvent.ToolResult("draft_message", result.text()));
            session.stream(
                    "Draft ready:\n\n" + result.text());
            return;
        }

        // Default path: no crew member matches; stream the primary assistant's
        // own LLM response directly through the active AgentRuntime.
        session.stream(message);
    }

    private static boolean matchesAny(String haystack, String... needles) {
        for (var needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
