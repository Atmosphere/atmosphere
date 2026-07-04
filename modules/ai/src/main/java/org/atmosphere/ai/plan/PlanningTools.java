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
package org.atmosphere.ai.plan;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
import org.atmosphere.ai.tool.ToolKind;
import org.atmosphere.ai.tool.ToolScopes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the built-in {@code write_todos} tool — the portable planning floor
 * every runtime gets when the harness PLANNING feature resolves and no native
 * plan surface wins (see {@link PlanningMode}).
 *
 * <p>Semantics are deepagents / Claude-Code parity: each call replaces the
 * <em>full</em> todo list ({@code {content, status, activeForm}} items), the
 * plan persists per conversation through the {@link AgentPlanStore} resolved
 * from the injectables seam, every change emits an
 * {@link AiEvent.PlanUpdate} so consoles render the live plan, and the tool
 * returns the plan as a Markdown checklist so the model sees the state it
 * just wrote.</p>
 */
public final class PlanningTools {

    /** The tool name surfaced to the model. */
    public static final String WRITE_TODOS = "write_todos";

    /** Parses string-encoded {@code todos} arguments (same flavor as the wire). */
    private static final ObjectMapper WIRE_MAPPER = new ObjectMapper();

    private static final String DESCRIPTION =
            "Create or update your task plan. Pass the FULL todo list every time - "
            + "this replaces the previous list. Each item has 'content' (imperative, "
            + "e.g. 'Run tests'), 'status' ('pending', 'in_progress', 'completed' or "
            + "'abandoned') and optional 'activeForm' (present continuous, e.g. "
            + "'Running tests'). Keep exactly one item 'in_progress' at a time. Use "
            + "this for any multi-step task so progress stays visible.";

    private PlanningTools() {
    }

    /**
     * The {@code write_todos} {@link ToolDefinition} to register when the
     * built-in planning floor applies.
     *
     * @param defaultAgentId the registration-time owner (agent / endpoint
     *                       name) used to key the plan when the request
     *                       carries no {@code ai.agentId} attribute
     * @return the tool definition, never {@code null}
     */
    public static ToolDefinition writeTodosTool(String defaultAgentId) {
        return ToolDefinition.builder(WRITE_TODOS, DESCRIPTION)
                .parameter("todos",
                        "The full todo list: an array of {content, status, activeForm} objects",
                        "array", true)
                .parameter("goal",
                        "Optional one-line goal the todos work toward",
                        "string", false)
                .returnType("string")
                .executor(executor(defaultAgentId))
                .kind(ToolKind.EDIT)
                .build();
    }

    private static ToolExecutor executor(String defaultAgentId) {
        return new ToolExecutor() {
            @Override
            public Object execute(Map<String, Object> arguments) throws Exception {
                return execute(arguments, Map.of());
            }

            @Override
            public Object execute(Map<String, Object> arguments,
                                  Map<Class<?>, Object> injectables) throws Exception {
                return run(arguments, injectables, defaultAgentId);
            }
        };
    }

    private static Object run(Map<String, Object> arguments,
                              Map<Class<?>, Object> injectables,
                              String defaultAgentId) {
        var scope = injectables != null ? injectables : Map.<Class<?>, Object>of();
        if (!(scope.get(AgentPlanStore.class) instanceof AgentPlanStore store)) {
            return "write_todos unavailable: no plan store is bound to this session.";
        }
        AgentPlan plan;
        try {
            plan = parsePlan(arguments);
        } catch (IllegalArgumentException e) {
            return "write_todos failed: " + e.getMessage();
        }
        var agentId = ToolScopes.agentId(scope, defaultAgentId);
        var conversationId = ToolScopes.conversationId(scope);
        try {
            store.put(agentId, conversationId, plan);
        } catch (IllegalArgumentException e) {
            // Bounds rejection (Correctness Invariant #3) — surface the clear
            // message to the model instead of a stack trace.
            return "write_todos failed: " + e.getMessage();
        }
        StreamingSession session = ToolScopes.session(scope);
        if (session != null) {
            session.emit(new AiEvent.PlanUpdate(plan.toWireSteps(), plan.goal()));
        }
        return plan.toMarkdown();
    }

    /**
     * Parse the model-supplied arguments into an {@link AgentPlan}. Lenient
     * on shape (status strings parse through {@link PlanStatus#parse},
     * {@code activeForm} is optional, and a JSON-encoded string array is
     * accepted — real models routinely double-encode nested tool arguments)
     * but strict on structure: each todo needs a non-blank {@code content}.
     */
    static AgentPlan parsePlan(Map<String, Object> arguments) {
        var args = arguments != null ? arguments : Map.<String, Object>of();
        var rawTodos = coerceTodoList(args.get("todos"));
        if (!(rawTodos instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "'todos' is required and must be an array of {content, status, activeForm}");
        }
        var steps = new ArrayList<AgentPlan.Step>(list.size());
        for (var item : list) {
            if (!(item instanceof Map<?, ?> todo)) {
                throw new IllegalArgumentException(
                        "each todo must be an object with 'content' and 'status'");
            }
            var content = stringOrNull(todo.get("content"));
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("each todo needs a non-empty 'content'");
            }
            steps.add(new AgentPlan.Step(content,
                    PlanStatus.parse(stringOrNull(todo.get("status"))),
                    stringOrNull(todo.get("activeForm"))));
        }
        return new AgentPlan(stringOrNull(args.get("goal")), steps);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Accept a {@code todos} value that arrived as a JSON-encoded string —
     * providers and models routinely serialize nested tool arguments as a
     * string instead of a structured array (battle-tested: Gemini via the
     * built-in runtime). Anything unparseable is returned as-is so the
     * caller's structure check produces the clear tool error.
     */
    private static Object coerceTodoList(Object rawTodos) {
        if (rawTodos instanceof String json && !json.isBlank()) {
            try {
                return WIRE_MAPPER.readValue(json.trim(), List.class);
            } catch (JacksonException e) {
                return rawTodos;
            }
        }
        return rawTodos;
    }
}
