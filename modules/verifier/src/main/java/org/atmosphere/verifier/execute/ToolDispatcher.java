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
package org.atmosphere.verifier.execute;

import java.util.Map;

/**
 * Pluggable strategy for dispatching a single tool call after SymRef
 * resolution. The default implementation
 * ({@link RegistryToolDispatcher}) delegates straight to
 * {@link org.atmosphere.ai.tool.ToolRegistry#execute}; a Phase 2 gated
 * dispatcher will route through
 * {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval}
 * to preserve {@code @RequiresApproval} gates and the existing audit
 * trail.
 *
 * <p>Returns the tool's result as a String — matching the JSON-string
 * return convention of {@link org.atmosphere.ai.tool.ToolResult#result()}.
 * Failures must be raised as exceptions; the executor wraps them in a
 * {@link WorkflowExecutionException} that carries the partial environment.
 * </p>
 */
@FunctionalInterface
public interface ToolDispatcher {

    /**
     * Invoke a tool with already-resolved arguments.
     *
     * @param toolName the tool name; matches a registered
     *                 {@link org.atmosphere.ai.tool.ToolDefinition}.
     * @param args     argument map with all SymRefs already resolved to
     *                 their bound values.
     * @return the tool's result, conventionally a JSON string.
     * @throws RuntimeException if the tool fails; the executor wraps it.
     */
    String dispatch(String toolName, Map<String, Object> args);
}
