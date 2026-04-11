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
package org.atmosphere.ai.approval;

import org.atmosphere.ai.tool.ToolDefinition;

import java.util.function.Predicate;

/**
 * Decides whether a tool invocation must pass through the session-scoped
 * {@link ApprovalStrategy} before the underlying executor runs. Phase 6 of
 * the unified {@code @Agent} API promotes the implicit "every tool with
 * {@code @RequiresApproval}" behavior from Phase 0 into a first-class policy
 * on {@code AgentExecutionContext}, so applications can:
 *
 * <ul>
 *   <li>Allow every tool unconditionally (trusted test harnesses).</li>
 *   <li>Deny every tool unconditionally (shadow/preview mode).</li>
 *   <li>Keep the annotation-driven default (Phase 0 behavior).</li>
 *   <li>Provide a custom predicate that inspects the tool at runtime.</li>
 * </ul>
 *
 * <p>Phase 0 ships the wiring (every runtime bridge routes through
 * {@code ToolExecutionHelper.executeWithApproval}); this phase just adds the
 * policy hook so callers can override the per-tool behavior without
 * re-annotating every {@code @AiTool}. Runtimes that consult
 * {@code context.approvalPolicy()} should call {@link #requiresApproval}
 * before deciding whether to gate the tool invocation.</p>
 */
public sealed interface ToolApprovalPolicy {

    /**
     * @return {@code true} when the given tool invocation must pass through
     * the approval gate before running its executor.
     */
    boolean requiresApproval(ToolDefinition tool);

    /** The annotation-driven default: honors {@code @RequiresApproval}. */
    static ToolApprovalPolicy annotated() {
        return Annotated.INSTANCE;
    }

    /** Allow every tool through without gating (e.g. trusted test fixtures). */
    static ToolApprovalPolicy allowAll() {
        return AllowAll.INSTANCE;
    }

    /** Deny every tool (no invocation ever runs — preview/shadow mode). */
    static ToolApprovalPolicy denyAll() {
        return DenyAll.INSTANCE;
    }

    /** Custom predicate evaluated per tool at invocation time. */
    static ToolApprovalPolicy custom(Predicate<ToolDefinition> predicate) {
        return new Custom(predicate);
    }

    /** Default policy — respects the {@code @RequiresApproval} annotation. */
    record Annotated() implements ToolApprovalPolicy {
        static final Annotated INSTANCE = new Annotated();

        @Override
        public boolean requiresApproval(ToolDefinition tool) {
            return tool != null && tool.requiresApproval();
        }
    }

    /** Trusting policy — never gates. */
    record AllowAll() implements ToolApprovalPolicy {
        static final AllowAll INSTANCE = new AllowAll();

        @Override
        public boolean requiresApproval(ToolDefinition tool) {
            return false;
        }
    }

    /** Paranoid policy — gates everything (preview mode). */
    record DenyAll() implements ToolApprovalPolicy {
        static final DenyAll INSTANCE = new DenyAll();

        @Override
        public boolean requiresApproval(ToolDefinition tool) {
            return true;
        }
    }

    /** Caller-supplied predicate. {@code predicate} must be non-null and thread-safe. */
    record Custom(Predicate<ToolDefinition> predicate) implements ToolApprovalPolicy {
        public Custom {
            if (predicate == null) {
                throw new IllegalArgumentException("predicate must not be null");
            }
        }

        @Override
        public boolean requiresApproval(ToolDefinition tool) {
            return predicate.test(tool);
        }
    }
}
