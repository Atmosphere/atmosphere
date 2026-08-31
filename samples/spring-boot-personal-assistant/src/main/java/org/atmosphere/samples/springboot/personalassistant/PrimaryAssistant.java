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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import java.security.Principal;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;
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
 * (scheduler, research, drafter) through <b>framework-agnostic
 * {@code @AiTool} methods</b>. The LLM decides which crew member to
 * invoke based on tool descriptions; the tool method body forwards the
 * call through {@code AgentFleet} over {@code InMemoryProtocolBridge}.
 *
 * <p>When no LLM API key is configured, the sample falls back to a
 * deterministic keyword router so the demo still runs end-to-end without
 * credentials. Set {@code OPENAI_API_KEY} (or the equivalent for your
 * runtime) to exercise the full LLM-driven tool-calling path.</p>
 *
 * <h2>Primitive integration</h2>
 *
 * <ul>
 *   <li>{@code @AiTool} — the three crew-dispatch methods are registered
 *       in the tool registry and bridged to whichever runtime is active
 *       (Spring AI, LangChain4j, ADK, etc.)</li>
 *   <li>{@code @Coordinator} / {@code @Fleet} / {@code AgentFleet} — crew
 *       members are dispatched over {@code InMemoryProtocolBridge}</li>
 *   <li>{@code AgentState} — the file-backed workspace persists what the
 *       agent writes for the life of the conversation. The scope is the
 *       conversation id, which {@code AiEndpointHandler} defaults to the
 *       connection's {@code resource.uuid()} unless the application sets the
 *       {@code ai.conversationId} request attribute — so out of the box a
 *       reconnect starts an empty workspace. Set that attribute to a stable
 *       per-user id to get memory that outlives a connection; the default is
 *       deliberately per-connection because sharing a workspace between
 *       unidentified callers would leak one user's files to the next.</li>
 *   <li>{@code AgentIdentity} — permission modes layer over tool approval
 *       (no destructive tools here; all three are safe to auto-approve)</li>
 *   <li>{@code ToolExtensibilityPoint} — per-user MCP servers loaded from
 *       {@code .agent-workspace/MCP.md} surface to the crew at runtime</li>
 *   <li>{@code AiGateway} — the outbound LLM call traverses the gateway
 *       choke point for rate limiting and credential resolution</li>
 * </ul>
 */
@Coordinator(
        name = "primary-assistant",
        skillFile = "skill:primary-assistant",
        description = "Personal assistant that delegates to a scheduler, research, and drafter crew via @AiTool.")
@Fleet({
        @AgentRef(type = SchedulerAgent.class),
        @AgentRef(type = ResearchAgent.class),
        @AgentRef(type = DrafterAgent.class)
})
@AgentScope(unrestricted = true,
        justification = "Personal-assistant demo — the sample is intentionally broad "
                + "(scheduling + research + drafting) so no single purpose captures it. "
                + "A production personal assistant would either scope per user or keep "
                + "this unrestricted and rely on the downstream crew members' own scopes.")
public class PrimaryAssistant {

    private static final Logger logger = LoggerFactory.getLogger(PrimaryAssistant.class);

    /**
     * Key long-term memory on the authenticated principal.
     *
     * <p>{@code AuthInterceptor} validates the Console's sign-in token and stores
     * the resolved principal under {@link FrameworkConfig#AUTH_PRINCIPAL}. It does
     * NOT set {@code ai.userId}, and {@code AiEndpointHandler.resolveRunOwner}
     * reads only {@code ai.userId} or the servlet {@code getUserPrincipal()} —
     * which token auth never populates. Without this bridge an authenticated
     * caller still falls through to {@code anonymous}, so every visitor shares one
     * memory bucket.</p>
     *
     * <p>An earlier version read a {@code ?user=} query parameter instead. That
     * never worked: the Console forwards only {@code token} onto the transport, so
     * the parameter never arrived and user B was told user A's facts. The
     * 2026-08-31 sweep caught it; the unit test that "covered" it hand-built a
     * request already carrying the parameter, so it passed while proving nothing
     * about the production path.</p>
     */
    @Ready
    public void onReady(AtmosphereResource resource) {
        var request = resource.getRequest();
        if (request == null || request.getAttribute("ai.userId") != null) {
            return;
        }
        if (request.getAttribute(FrameworkConfig.AUTH_PRINCIPAL) instanceof Principal p
                && p.getName() != null && !p.getName().isBlank()) {
            request.setAttribute("ai.userId", p.getName());
        }
        // No authenticated principal -> no identity -> no memory. Deliberately not
        // defaulting: a shared fallback identity is exactly what leaked facts.
    }

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        logger.info("Primary assistant received: {}", message);
        // Wrap the fleet with a streaming activity listener so tool-call
        // cards render in the Atmosphere AI Console. Publish the wrapped
        // instance into the session's injectable scope so the @AiTool
        // methods below receive the same wrapped fleet the LLM-driven
        // tool-call loop would have seen.
        var wrapped = fleet.withActivityListener(new StreamingActivityListener(session));
        if (session instanceof org.atmosphere.ai.AiStreamingSession s) {
            s.putInjectable(AgentFleet.class, wrapped);
        }

        // A locally served backend (Ollama, vLLM, LM Studio) needs no
        // credential, so an absent key says nothing about whether a model is
        // reachable. Keying only on the key sent every keyless-local run down
        // the keyword fallback — the sample answered "Configure OPENAI_API_KEY"
        // while a model was running and idle, and the long-term-memory headline
        // was unreachable because nothing ever called the tools.
        var settings = AiConfig.get();
        boolean hasLlm = settings != null && settings.hasReachableModel();

        if (hasLlm) {
            // LLM-driven path: stream the user message through the runtime.
            // The tool-call loop in OpenAiCompatibleClient (or the equivalent
            // in Spring AI / LangChain4j / ADK / Koog / SK / Embabel) sees the
            // three @AiTool methods below, picks the right one, and the
            // framework injects the live AgentFleet as a typed parameter —
            // no ThreadLocal required.
            session.stream(message);
        } else {
            // Fallback: keyword router with explicit tool dispatch so the
            // demo runs without an API key.
            runKeywordFallback(message, session, wrapped);
        }
    }

    // ---------- @AiTool methods exposed to the LLM ----------

    /**
     * "Commit" action — schedules a meeting. Gated by
     * {@link org.atmosphere.ai.annotation.RequiresApproval}
     * so the user confirms before anything lands on the (hypothetical)
     * calendar. Gives the foundation's PermissionMode + @RequiresApproval
     * pipeline a live exerciser: set
     * {@code AgentIdentity.setPermissionMode(userId, PermissionMode.PLAN)}
     * and every tool — including read-only ones — will route through the
     * approval gate, confirming the outer policy takes precedence.
     */
    @AiTool(name = "schedule_meeting",
            description = "Propose meeting slots for a given topic. Call this when "
                    + "the user wants to schedule, book, or arrange a meeting.")
    @org.atmosphere.ai.annotation.RequiresApproval(
            "Confirm scheduling this meeting? (Click approve to propose slots, deny to cancel.)")
    public String scheduleMeeting(
            AgentFleet fleet,
            @Param(value = "topic", description = "What the meeting is about") String topic,
            @Param(value = "date_hint",
                    description = "Optional ISO-8601 date (YYYY-MM-DD), empty for today") String dateHint) {
        var result = fleet.agent("scheduler-agent")
                .call("propose_slots", Map.of(
                        "topic", topic,
                        "date_hint", dateHint == null ? "" : dateHint));
        return result.text();
    }

    @AiTool(name = "research_topic",
            description = "Research a topic and return a short brief. Call this when "
                    + "the user wants to know about a topic, get context, or look something up.")
    public String researchTopic(
            AgentFleet fleet,
            @Param(value = "topic", description = "The topic to research") String topic) {
        var result = fleet.agent("research-agent")
                .call("summarize_topic", Map.of("topic", topic));
        return result.text();
    }

    @AiTool(name = "draft_message",
            description = "Draft a short-form message for a recipient. Call this when "
                    + "the user wants a note, reply, email, or message drafted.")
    public String draftMessage(
            AgentFleet fleet,
            @Param(value = "recipient",
                    description = "Who the message is for — a person or team name") String recipient,
            @Param(value = "intent",
                    description = "What the message needs to convey") String intent) {
        var result = fleet.agent("drafter-agent")
                .call("draft_message", Map.of(
                        "recipient", recipient == null || recipient.isBlank() ? "team" : recipient,
                        "intent", intent));
        return result.text();
    }

    // ---------- Fallback path (no LLM) ----------

    private void runKeywordFallback(String message, StreamingSession session, AgentFleet fleet) {
        var lower = message.toLowerCase(Locale.ROOT);
        if (matchesAny(lower, "schedule", "meeting", "book")) {
            emitToolCall(session, "schedule_meeting",
                    Map.of("topic", message, "date_hint", ""),
                    scheduleMeeting(fleet, message, ""));
            return;
        }
        if (matchesAny(lower, "research", "look up", "what do you know")) {
            emitToolCall(session, "research_topic",
                    Map.of("topic", message),
                    researchTopic(fleet, message));
            return;
        }
        if (matchesAny(lower, "draft", "write", "email", "reply", "note")) {
            emitToolCall(session, "draft_message",
                    Map.of("recipient", "team", "intent", message),
                    draftMessage(fleet, "team", message));
            return;
        }
        // send() + complete() — NOT stream() — because this is the
        // keyless-demo path that must NOT dispatch to an LLM. Same failure
        // class as the coding-agent regression we fixed earlier: stream()
        // on AiStreamingSession treats the argument as a fresh user-turn
        // input and round-trips it to the model.
        session.send(
                "I can schedule meetings, research topics, or draft messages. "
                + "Configure OPENAI_API_KEY to let me pick the right tool automatically; "
                + "otherwise try keywords like 'schedule', 'research', or 'draft'.");
        session.complete();
    }

    private static void emitToolCall(StreamingSession session, String toolName,
                                      Map<String, Object> args, String result) {
        session.emit(new AiEvent.ToolStart(toolName, args));
        session.emit(new AiEvent.ToolResult(toolName, result));
        // Keyless fallback streams the tool result directly to the client
        // via send() + complete(); stream() would re-dispatch the result
        // as an LLM prompt and (without credentials) error out.
        session.send(result);
        session.complete();
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
