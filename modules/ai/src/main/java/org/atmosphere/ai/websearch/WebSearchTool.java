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
package org.atmosphere.ai.websearch;

import java.util.Map;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
import org.atmosphere.ai.tool.ToolKind;

/**
 * Builds the {@code web_search} tool — the single web-search surface offered to
 * the model. The model supplies a query; the tool runs it through the resolved
 * {@link WebSearchEngine} (via {@link WebSearchSupport#shared()}) and returns a
 * ranked, model-readable list of results.
 *
 * <p>Tagged {@link ToolKind#NETWORK} so a {@code ToolApprovalPolicy} or
 * governance policy can gate outbound search exactly like other network
 * surfaces. The tool is <em>fail-closed</em>: when no engine is configured it
 * returns a clear "not configured" result rather than throwing or touching the
 * network (Correctness Invariants #5, #6), and it is only registered with an
 * endpoint's registry when {@link WebSearchSupport#isEnabled()} confirms a
 * configured engine.</p>
 */
public final class WebSearchTool {

    /** The tool name surfaced to the model. */
    public static final String TOOL_NAME = "web_search";

    private static final String DESCRIPTION =
            "Search the public web for current information — news, facts, documentation, "
            + "market and competitor data — and return a ranked list of results, each with "
            + "a title, URL, and short snippet. Use it when a question needs information "
            + "beyond your training data. Read-only: it fetches and summarizes, it does not "
            + "browse or act.";

    private WebSearchTool() {
    }

    /** The {@link ToolDefinition} to register when {@link WebSearchSupport#isEnabled()}. */
    public static ToolDefinition definition() {
        return ToolDefinition.builder(TOOL_NAME, DESCRIPTION)
                .parameter("query", "The search query", "string", true)
                .parameter("num_results",
                        "Maximum number of results to return (1-" + WebSearchQuery.HARD_CAP + ")",
                        "integer", false)
                .returnType("string")
                .executor(executor())
                .kind(ToolKind.NETWORK)
                .build();
    }

    private static ToolExecutor executor() {
        return new ToolExecutor() {
            @Override
            public Object execute(Map<String, Object> arguments) throws Exception {
                return execute(arguments, Map.of());
            }

            @Override
            public Object execute(Map<String, Object> arguments,
                                  Map<Class<?>, Object> injectables) {
                var query = string(arguments, "query");
                if (query == null || query.isBlank()) {
                    return "Error: 'query' is required";
                }
                int count = parseCount(arguments == null ? null : arguments.get("num_results"));
                return WebSearchSupport.shared()
                        .search(WebSearchQuery.of(query, count))
                        .toModelText();
            }
        };
    }

    /** Parse the model-supplied result count; default when absent or unparseable. */
    static int parseCount(Object raw) {
        if (raw == null) {
            return WebSearchQuery.DEFAULT_MAX_RESULTS;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return WebSearchQuery.DEFAULT_MAX_RESULTS;
        }
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }
}
