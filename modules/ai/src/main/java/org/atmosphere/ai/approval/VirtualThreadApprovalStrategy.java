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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Default approval strategy: parks the virtual thread on a
 * {@code CompletableFuture.get(timeout)}. The VT is cheap to park — no
 * carrier thread is consumed (NIO SelectionKey analogy). The future is
 * completed by the {@link ApprovalRegistry} when the client sends
 * {@code /__approval/<id>/approve} or {@code /__approval/<id>/deny}.
 */
final class VirtualThreadApprovalStrategy implements ApprovalStrategy {

    private static final Logger logger = LoggerFactory.getLogger(VirtualThreadApprovalStrategy.class);

    private final ApprovalRegistry registry;

    VirtualThreadApprovalStrategy(ApprovalRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
        // Register the future before emitting the event to avoid race conditions
        var future = registry.register(approval);

        // Emit approval request to client
        var expiresIn = Duration.between(Instant.now(), approval.expiresAt()).toSeconds();
        session.emit(new AiEvent.ApprovalRequired(
                approval.approvalId(),
                approval.toolName(),
                approval.arguments(),
                approval.message(),
                expiresIn
        ));

        logger.debug("Waiting for approval {} (tool: {}, expires in {}s)",
                approval.approvalId(), approval.toolName(), expiresIn);

        // Park the virtual thread — cheap, no carrier thread consumed
        try {
            var approved = registry.awaitApproval(approval, future);
            return approved ? ApprovalOutcome.APPROVED : ApprovalOutcome.DENIED;
        } catch (ApprovalRegistry.ApprovalTimeoutException e) {
            logger.debug("Approval {} timed out", approval.approvalId());
            return ApprovalOutcome.TIMED_OUT;
        }
    }
}
