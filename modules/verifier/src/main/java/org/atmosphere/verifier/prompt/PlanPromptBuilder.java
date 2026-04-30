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
package org.atmosphere.verifier.prompt;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.policy.Policy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the system prompt that coaxes an LLM into emitting a JSON
 * {@link org.atmosphere.verifier.ast.Workflow} instead of free-form
 * tool calls.
 *
 * <p>The prompt has four parts:</p>
 * <ol>
 *   <li>The role + plan-only instruction.</li>
 *   <li>The list of tools the LLM is permitted to use, derived from the
 *       intersection of {@link Policy#allowedTools()} and the
 *       {@link ToolRegistry}. This is what makes the verifier's allowlist
 *       check non-redundant — the prompt only advertises permitted tools,
 *       the verifier enforces it.</li>
 *   <li>The wire-format schema instructions sourced from
 *       {@link WorkflowJsonParser#schemaInstructions()} so the schema and
 *       the parser cannot drift.</li>
 *   <li>A worked example illustrating the {@code @binding} convention.</li>
 * </ol>
 *
 * <p>The builder is deliberately a value-bag with no mutable state — calling
 * {@link #build(String)} multiple times produces identical output for the
 * same inputs, which makes the produced prompts easy to snapshot-test and
 * cache-friendly when prompt caching is enabled upstream.</p>
 */
public final class PlanPromptBuilder {

    private final Policy policy;
    private final ToolRegistry registry;
    private final WorkflowJsonParser parser;

    public PlanPromptBuilder(Policy policy, ToolRegistry registry) {
        this(policy, registry, new WorkflowJsonParser());
    }

    public PlanPromptBuilder(Policy policy, ToolRegistry registry, WorkflowJsonParser parser) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * Build the system prompt. The {@code userGoal} is appended verbatim
     * as the natural-language goal the LLM must encode into a workflow.
     */
    public String build(String userGoal) {
        Objects.requireNonNull(userGoal, "userGoal");
        var sb = new StringBuilder();
        sb.append("You are a planning agent. Your only job is to emit a JSON workflow.\n");
        sb.append("DO NOT call tools directly. DO NOT explain. Output JSON only.\n");
        sb.append('\n');
        sb.append("Permitted tools:\n");
        appendToolList(sb);
        sb.append('\n');
        sb.append("Wire format:\n");
        sb.append(parser.schemaInstructions());
        sb.append('\n');
        sb.append('\n');
        sb.append("Conventions:\n");
        sb.append("- Each step has a unique 'label' (free-form, for diagnostics).\n");
        sb.append("- 'toolName' MUST be one of the permitted tools listed above.\n");
        sb.append("- 'arguments' is a JSON object passed to the tool as-is.\n");
        sb.append("- 'resultBinding' names the variable that captures the tool's result.\n");
        sb.append("- To pass a previous step's binding into a later step, use the\n");
        sb.append("  literal string \"@<binding-name>\" — e.g. \"@emails\". A leading\n");
        sb.append("  '@' is a symbolic reference; \"@@text\" escapes to the literal \"@text\".\n");
        sb.append("- A step that does not need to bind its result may omit\n");
        sb.append("  'resultBinding' or set it to null.\n");
        sb.append('\n');
        sb.append("Example:\n");
        sb.append(EXAMPLE_PLAN);
        sb.append('\n');
        sb.append('\n');
        sb.append("Goal: ").append(userGoal).append('\n');
        return sb.toString();
    }

    private void appendToolList(StringBuilder sb) {
        Collection<String> permitted = permittedToolNames();
        if (permitted.isEmpty()) {
            sb.append("(none — refuse to plan)\n");
            return;
        }
        for (String name : permitted) {
            ToolDefinition def = registry.getTool(name).orElse(null);
            if (def == null) {
                continue;
            }
            sb.append("- ").append(def.name()).append(": ")
                    .append(def.description()).append('\n');
            for (ToolParameter p : def.parameters()) {
                sb.append("    * ").append(p.name())
                        .append(" (").append(p.type()).append(p.required() ? ", required" : "")
                        .append("): ").append(p.description()).append('\n');
            }
        }
    }

    private Set<String> permittedToolNames() {
        var permitted = new LinkedHashSet<String>();
        for (String name : policy.allowedTools()) {
            if (registry.getTool(name).isPresent()) {
                permitted.add(name);
            }
        }
        return permitted;
    }

    private static final String EXAMPLE_PLAN = """
            {
              "goal": "Fetch unread emails and summarize them",
              "steps": [
                {
                  "label": "fetch",
                  "toolName": "fetch_emails",
                  "arguments": { "folder": "inbox" },
                  "resultBinding": "emails"
                },
                {
                  "label": "summarize",
                  "toolName": "summarize",
                  "arguments": { "input": "@emails" },
                  "resultBinding": "summary"
                }
              ]
            }""";
}
