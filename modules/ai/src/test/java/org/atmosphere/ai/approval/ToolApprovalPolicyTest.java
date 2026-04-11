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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 contract: {@link ToolApprovalPolicy} is a sealed hierarchy with four
 * built-in cases — the annotation-driven default used by Phase 0 plus three
 * overrides applications can pick up without re-annotating tools.
 */
class ToolApprovalPolicyTest {

    private static ToolDefinition annotated() {
        return ToolDefinition.builder("delete", "delete a row")
                .parameter("id", "row id", "string")
                .executor(args -> "deleted")
                .requiresApproval("Are you sure?")
                .build();
    }

    private static ToolDefinition plain() {
        return ToolDefinition.builder("echo", "echo")
                .parameter("v", "value", "string")
                .executor(args -> args.get("v"))
                .build();
    }

    @Test
    void annotatedPolicyMirrorsRequiresApprovalFlag() {
        var policy = ToolApprovalPolicy.annotated();
        assertTrue(policy.requiresApproval(annotated()));
        assertFalse(policy.requiresApproval(plain()));
        assertFalse(policy.requiresApproval(null));
    }

    @Test
    void allowAllIgnoresTheAnnotation() {
        var policy = ToolApprovalPolicy.allowAll();
        assertFalse(policy.requiresApproval(annotated()));
        assertFalse(policy.requiresApproval(plain()));
    }

    @Test
    void denyAllGatesEveryInvocation() {
        var policy = ToolApprovalPolicy.denyAll();
        assertTrue(policy.requiresApproval(annotated()));
        assertTrue(policy.requiresApproval(plain()));
    }

    @Test
    void customPolicyDelegatesToPredicate() {
        var policy = ToolApprovalPolicy.custom(t -> "delete".equals(t.name()));
        assertTrue(policy.requiresApproval(annotated()));
        assertFalse(policy.requiresApproval(plain()));
    }

    @Test
    void customPolicyRejectsNullPredicate() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolApprovalPolicy.Custom(null));
    }
}
