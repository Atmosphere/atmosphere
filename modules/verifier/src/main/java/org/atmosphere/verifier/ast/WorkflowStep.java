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

import java.util.Objects;

/**
 * A single step in a {@link Workflow}. Wraps a {@link PlanNode} alongside a
 * human-readable {@code label} so verifier diagnostics and execution traces
 * can refer to steps by name rather than index.
 *
 * <p>The wrapping layer is deliberately separate from the AST node so that
 * future Phase 2+ work can attach step-level metadata
 * (timeouts, retry hints, telemetry tags) without invalidating the
 * {@code PlanNode} sealed hierarchy.</p>
 *
 * @param label a short, human-readable identifier; non-blank.
 * @param node  the AST node executed at this step.
 */
public record WorkflowStep(String label, PlanNode node) {
    public WorkflowStep {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        Objects.requireNonNull(node, "node");
    }
}
