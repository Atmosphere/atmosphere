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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.orchestration.InvocationContext;
import com.microsoft.semantickernel.orchestration.ToolCallBehavior;
import org.atmosphere.ai.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-request Semantic Kernel {@link InvocationContext} sidecar
 * ({@link SemanticKernelInvocation}). Without an attached context the runtime
 * builds its default via
 * {@link SemanticKernelAgentRuntime#buildInvocationContext(boolean)}; with one
 * attached it must dispatch verbatim against the caller-provided context, so
 * power features like {@code KernelHooks} and {@code withMaxAutoInvokeAttempts}
 * actually flow through. The runtime read path is exercised end-to-end by
 * {@code SemanticKernelRuntimeContractTest}; this test pins the helper-level
 * semantics.
 */
class SemanticKernelInvocationBridgeTest {

    @Test
    void fromReturnsNullWhenNoSlot() {
        var ctx = baseContext(Map.of());
        assertNull(SemanticKernelInvocation.from(ctx),
                "missing slot must yield null so the runtime falls back to "
                        + "buildInvocationContext(hasTools)");
    }

    @Test
    void fromReturnsNullWhenContextIsNull() {
        assertNull(SemanticKernelInvocation.from(null),
                "null context must yield null rather than NPE");
    }

    @Test
    void fromRejectsNonInvocationContextSlot() {
        var ctx = baseContext(Map.of(SemanticKernelInvocation.METADATA_KEY, 42));
        var iae = assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelInvocation.from(ctx),
                "a non-InvocationContext slot must fail loudly — silently "
                        + "dropping the override would mask the per-request hooks "
                        + "/ tool-call behavior never firing");
        assertTrue(iae.getMessage().contains(SemanticKernelInvocation.METADATA_KEY));
        assertTrue(iae.getMessage().contains(InvocationContext.class.getName()));
    }

    @Test
    void attachStoresContextUnderCanonicalKey() {
        var invocation = newInvocationContext();
        var ctx = SemanticKernelInvocation.attach(baseContext(Map.of()), invocation);
        assertSame(invocation, ctx.metadata().get(SemanticKernelInvocation.METADATA_KEY));
        assertSame(invocation, SemanticKernelInvocation.from(ctx),
                "round-trip from(attach(ctx, ic)) must return ic");
    }

    @Test
    void attachReplacesPreviousContext() {
        var first = newInvocationContext();
        var second = newInvocationContext();
        var withFirst = SemanticKernelInvocation.attach(baseContext(Map.of()), first);
        var withSecond = SemanticKernelInvocation.attach(withFirst, second);
        assertSame(second, SemanticKernelInvocation.from(withSecond),
                "a request has exactly one InvocationContext — attach replaces");
        assertNotSame(withFirst, withSecond);
    }

    @Test
    void attachRejectsNullArgs() {
        var ctx = baseContext(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelInvocation.attach(ctx, null),
                "null invocation context must fail loudly at attach time");
        assertThrows(IllegalArgumentException.class,
                () -> SemanticKernelInvocation.attach(null, newInvocationContext()),
                "null context must fail loudly at attach time");
    }

    @Test
    void attachPreservesOtherMetadataEntries() {
        var ctx = baseContext(Map.of("other.key", "preserved"));
        var with = SemanticKernelInvocation.attach(ctx, newInvocationContext());
        assertEquals("preserved", with.metadata().get("other.key"),
                "attach must not clobber unrelated metadata entries");
    }

    private static InvocationContext newInvocationContext() {
        return InvocationContext.builder()
                .withToolCallBehavior(ToolCallBehavior.allowAllKernelFunctions(false))
                .build();
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
    }
}
