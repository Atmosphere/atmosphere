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
package org.atmosphere.ai.tool;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pre-dispatch authorization gate for tool invocations. Returns a
 * {@link ToolPermission} the executor honors instead of forcing every caller
 * to re-annotate {@code @AiTool} methods or hand-edit
 * {@link org.atmosphere.ai.approval.ToolApprovalPolicy} instances.
 *
 * <p>Complements (does not replace) the existing approval stack:</p>
 * <ul>
 *   <li>{@code @RequiresApproval} — annotation, decided at build time.</li>
 *   <li>{@link org.atmosphere.ai.approval.ToolApprovalPolicy} — sealed
 *       interface used inside the approval flow (annotated / allowAll /
 *       denyAll / custom).</li>
 *   <li>{@code ToolPermissionPolicy} — runtime, config-driven, per-tool
 *       tri-state. Wins over the annotation when its decision is more
 *       restrictive (DENY beats anything, CONFIRM beats unannotated ALLOW).</li>
 * </ul>
 *
 * <p>The default policy is {@link #ALLOW_ALL}. A custom implementation can be
 * installed via {@link #setGlobal(ToolPermissionPolicy)} or registered through
 * {@link ServiceLoader} (the first discovered implementation wins).</p>
 */
@FunctionalInterface
public interface ToolPermissionPolicy {

    /**
     * Decide how a tool invocation should be handled. Implementations must be
     * thread-safe and must not block.
     *
     * @param toolName the canonical tool name ({@code @AiTool.name()})
     * @param args     the parsed arguments the model supplied; never null
     * @return the resolved permission; never null
     */
    ToolPermission decide(String toolName, Map<String, Object> args);

    /** Always-allow policy. The framework default — preserves prior behavior. */
    ToolPermissionPolicy ALLOW_ALL = (toolName, args) -> ToolPermission.ALLOW;

    /** Always-deny policy. Useful for shadow/preview modes and tests. */
    ToolPermissionPolicy DENY_ALL = (toolName, args) -> ToolPermission.DENY;

    AtomicReference<ToolPermissionPolicy> GLOBAL = new AtomicReference<>(loadInitial());

    /**
     * @return the global {@link ToolPermissionPolicy}. Defaults to
     * {@link #ALLOW_ALL} unless overridden via {@link #setGlobal} or a
     * {@link ServiceLoader} entry.
     */
    static ToolPermissionPolicy global() {
        return GLOBAL.get();
    }

    /**
     * Replace the global policy. Returns the previous value so tests can
     * restore prior state in a {@code @AfterEach}.
     */
    static ToolPermissionPolicy setGlobal(ToolPermissionPolicy policy) {
        return GLOBAL.getAndSet(policy != null ? policy : ALLOW_ALL);
    }

    private static ToolPermissionPolicy loadInitial() {
        return ServiceLoader.load(ToolPermissionPolicy.class)
                .findFirst()
                .orElse(ALLOW_ALL);
    }
}
