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
package org.atmosphere.verifier.ast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single tool invocation in a {@link Workflow}. Argument map values are
 * either literal Java values (String, Number, Boolean, List, Map) or
 * {@link SymRef} placeholders — verifiers pattern-match on the value type
 * to distinguish.
 *
 * <p>{@code resultBinding} is optional. When present, the executor stores
 * the tool's return value under that name in the run environment, where
 * later steps can reference it via {@link SymRef}. When {@code null} or
 * blank, the result is discarded — fire-and-forget steps (e.g.
 * notifications) use this form.</p>
 *
 * <p><strong>Deep SymRef resolution is deferred to Phase 5.</strong> The
 * executor today resolves SymRefs only at the top level of the argument
 * map; nested SymRefs inside lists or sub-maps pass through unresolved.
 * Plans are expected to use top-level binding for now.</p>
 *
 * @param toolName       name of the tool to invoke; must match a registered
 *                       {@link org.atmosphere.ai.tool.ToolDefinition}.
 * @param arguments      argument map; values are literals or {@link SymRef}.
 * @param resultBinding  optional name under which to bind the result for
 *                       downstream steps; may be {@code null} or blank.
 */
public record ToolCallNode(String toolName,
                           Map<String, Object> arguments,
                           String resultBinding) implements PlanNode {

    public ToolCallNode {
        Objects.requireNonNull(toolName, "toolName");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments");
        // Defensive copy — arguments map is part of the AST and must be
        // immutable across the verify → execute boundary. LinkedHashMap
        // preserves insertion order so error paths surface arguments in a
        // deterministic order regardless of the caller's map type.
        arguments = Map.copyOf(new LinkedHashMap<>(arguments));
    }

    /**
     * Whether this call binds its result for downstream steps.
     */
    public boolean hasResultBinding() {
        return resultBinding != null && !resultBinding.isBlank();
    }
}
