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

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;
import java.util.Map;

/**
 * Sets the AI system prompt AND installs a per-request {@link ScopeConfig}
 * based on the {@code {room}} path parameter. Each room gets a different
 * persona AND a different scope so that:
 *
 * <ul>
 *   <li>the <b>math</b> room admits algebra, calculus, geometry and rejects
 *       "write me python code" as off-topic;</li>
 *   <li>the <b>code</b> room admits programming questions and rejects
 *       "solve this integral" as off-topic;</li>
 *   <li>the <b>science</b> room admits physics/chemistry/biology questions;</li>
 *   <li>the <b>general</b> room admits a broader educational band.</li>
 * </ul>
 *
 * <p>The per-room scope is installed by placing a {@link ScopeConfig} on
 * {@link AiRequest#metadata()} under {@link ScopePolicy#REQUEST_SCOPE_METADATA_KEY}.
 * {@code AiStreamingSession.stream} pops that key, builds a transient
 * {@link ScopePolicy}, runs pre-admission + system-prompt hardening, and
 * wraps the streamed response with the matching post-response check. This
 * is the framework-level alternative to a per-endpoint class per room.</p>
 *
 * <p>Demonstrates the {@link AiInterceptor} pattern: cross-cutting concerns
 * (persona selection, guardrails, RAG) without modifying the
 * {@code @Prompt} method.</p>
 */
public class RoomContextInterceptor implements AiInterceptor {

    private static final String DEFAULT_PROMPT =
            "You are a helpful classroom assistant. "
                    + "Answer questions clearly and concisely. Keep responses under 300 words.";

    private record Room(String prompt, ScopeConfig scope) { }

    private static final Map<String, Room> ROOMS = Map.of(
            "math", new Room(
                    "You are an expert mathematics tutor. "
                            + "Explain concepts clearly with step-by-step examples. "
                            + "Use precise mathematical notation. Keep responses under 300 words.",
                    new ScopeConfig(
                            "Mathematics tutoring — arithmetic, algebra, calculus, geometry, "
                                    + "statistics, proof technique",
                            List.of("writing source code", "programming tutorials"),
                            AgentScope.Breach.POLITE_REDIRECT,
                            "This is the math room — ask me about a mathematics topic instead.",
                            AgentScope.Tier.RULE_BASED, 0.45,
                            false, false, "")),
            "code", new Room(
                    "You are an expert software engineer and programming mentor. "
                            + "Provide clear, idiomatic code examples with explanations. "
                            + "Favor readability over cleverness. Keep responses under 300 words.",
                    new ScopeConfig(
                            "Software engineering mentoring — programming languages, debugging, "
                                    + "algorithms, design patterns, testing",
                            List.of("medical advice", "legal advice", "financial advice"),
                            AgentScope.Breach.POLITE_REDIRECT,
                            "This is the code room — ask me about a programming topic instead.",
                            AgentScope.Tier.RULE_BASED, 0.45,
                            false, false, "")),
            "science", new Room(
                    "You are a science educator specializing in physics, chemistry, and biology. "
                            + "Use analogies and real-world examples to make concepts accessible. "
                            + "Keep responses under 300 words.",
                    new ScopeConfig(
                            "Science education — physics, chemistry, biology, earth science, "
                                    + "scientific method",
                            List.of("writing source code", "programming tutorials",
                                    "medical diagnosis"),
                            AgentScope.Breach.POLITE_REDIRECT,
                            "This is the science room — ask me about a scientific topic instead.",
                            AgentScope.Tier.RULE_BASED, 0.45,
                            false, false, "")),
            "general", new Room(
                    DEFAULT_PROMPT,
                    new ScopeConfig(
                            "General educational assistance — broad-band tutoring across "
                                    + "academic subjects",
                            List.of("medical diagnosis", "legal advice", "financial advice"),
                            AgentScope.Breach.POLITE_REDIRECT,
                            "I can only help with general educational topics.",
                            AgentScope.Tier.RULE_BASED, 0.45,
                            false, false, ""))
    );

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var room = AiEndpointHandler.pathParam(resource, "room");
        if (room == null || room.isBlank()) {
            room = "general";
        }
        resource.getRequest().setAttribute("classroom.room", room);
        var selected = ROOMS.getOrDefault(room, ROOMS.get("general"));
        return request
                .withSystemPrompt(selected.prompt())
                .withMetadata(Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, selected.scope()));
    }

    /**
     * The {@link ScopeConfig} for a given room, exposed so the demo-fallback
     * {@code @Prompt} path (which bypasses {@code AiPipeline}) can carry the
     * same per-request scope into {@code PolicyAdmissionGate.admit}. Falls
     * back to the {@code general} room for unknown keys.
     */
    public static ScopeConfig scopeFor(String room) {
        var key = (room == null || room.isBlank()) ? "general" : room;
        return ROOMS.getOrDefault(key, ROOMS.get("general")).scope();
    }
}
