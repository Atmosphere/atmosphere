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

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.ai.tool.ToolResult;

import java.util.Map;
import java.util.Objects;

/**
 * Default {@link ToolDispatcher} — delegates to
 * {@link ToolRegistry#execute(String, Map)} and surfaces a
 * {@link ToolResult#success() failed} result as a runtime exception so
 * the executor can wrap it with partial-environment context.
 *
 * <p>Phase 2 introduces a sibling {@code GatedToolDispatcher} that
 * routes through
 * {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval}
 * and so requires a {@code StreamingSession} + approval policy at
 * construction time. The two are intended to be drop-in interchangeable
 * via this interface.</p>
 */
public final class RegistryToolDispatcher implements ToolDispatcher {

    private final ToolRegistry registry;

    public RegistryToolDispatcher(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String dispatch(String toolName, Map<String, Object> args) {
        ToolResult result = registry.execute(toolName, args);
        if (!result.success()) {
            throw new ToolDispatchException(toolName, result.error());
        }
        return result.result();
    }
}
