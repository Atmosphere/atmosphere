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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy.ApprovalOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VirtualThreadApprovalStrategyTest {

    private final ApprovalRegistry registry = mock(ApprovalRegistry.class);
    private final StreamingSession session = mock(StreamingSession.class);
    private final VirtualThreadApprovalStrategy strategy = new VirtualThreadApprovalStrategy(registry);

    private PendingApproval createApproval() {
        return new PendingApproval(
                "apr_test123",
                "delete_file",
                Map.of("path", "/important.txt"),
                "Allow deleting important.txt?",
                "conv-1",
                Instant.now().plusSeconds(60)
        );
    }

    @Test
    void approvedPathReturnsApproved() {
        var approval = createApproval();
        var future = new CompletableFuture<Boolean>();
        when(registry.register(approval)).thenReturn(future);
        when(registry.awaitApproval(eq(approval), eq(future))).thenReturn(true);

        var outcome = strategy.awaitApproval(approval, session);

        assertEquals(ApprovalOutcome.APPROVED, outcome);
        verify(registry).register(approval);
        verify(registry).awaitApproval(eq(approval), eq(future));
        verify(session).emit(any(AiEvent.ApprovalRequired.class));
    }

    @Test
    void deniedPathReturnsDenied() {
        var approval = createApproval();
        var future = new CompletableFuture<Boolean>();
        when(registry.register(approval)).thenReturn(future);
        when(registry.awaitApproval(eq(approval), eq(future))).thenReturn(false);

        var outcome = strategy.awaitApproval(approval, session);

        assertEquals(ApprovalOutcome.DENIED, outcome);
    }

    @Test
    void timeoutPathReturnsTimedOut() {
        var approval = createApproval();
        var future = new CompletableFuture<Boolean>();
        when(registry.register(approval)).thenReturn(future);
        when(registry.awaitApproval(eq(approval), eq(future)))
                .thenThrow(new ApprovalRegistry.ApprovalTimeoutException(approval));

        var outcome = strategy.awaitApproval(approval, session);

        assertEquals(ApprovalOutcome.TIMED_OUT, outcome);
    }

    @Test
    void emitsApprovalRequiredEventWithCorrectFields() {
        var approval = createApproval();
        var future = new CompletableFuture<Boolean>();
        when(registry.register(approval)).thenReturn(future);
        when(registry.awaitApproval(eq(approval), eq(future))).thenReturn(true);

        strategy.awaitApproval(approval, session);

        var captor = org.mockito.ArgumentCaptor.forClass(AiEvent.ApprovalRequired.class);
        verify(session).emit(captor.capture());
        var event = captor.getValue();
        assertEquals("apr_test123", event.approvalId());
        assertEquals("delete_file", event.toolName());
        assertEquals(Map.of("path", "/important.txt"), event.arguments());
        assertEquals("Allow deleting important.txt?", event.message());
    }

    @Test
    void registersBeforeEmitting() {
        var approval = createApproval();
        var future = new CompletableFuture<Boolean>();
        when(registry.register(approval)).thenReturn(future);
        when(registry.awaitApproval(eq(approval), eq(future))).thenReturn(true);

        strategy.awaitApproval(approval, session);

        var inOrder = org.mockito.Mockito.inOrder(registry, session);
        inOrder.verify(registry).register(approval);
        inOrder.verify(session).emit(any(AiEvent.ApprovalRequired.class));
        inOrder.verify(registry).awaitApproval(eq(approval), eq(future));
    }
}
