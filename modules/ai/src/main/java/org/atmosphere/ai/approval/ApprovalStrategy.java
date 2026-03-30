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

import org.atmosphere.ai.StreamingSession;

/**
 * Strategy for handling human-in-the-loop approval of tool executions.
 *
 * <p>The default implementation ({@link VirtualThreadApprovalStrategy}) parks
 * the virtual thread on a {@code CompletableFuture} — cheap and stateless.
 * The durable alternative serializes state to survive restarts.</p>
 */
public interface ApprovalStrategy {

    /**
     * Wait for human approval of a pending tool execution.
     *
     * @param approval the pending approval details
     * @param session  the streaming session (for emitting events to the client)
     * @return the outcome (approved, denied, or timed out)
     */
    ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session);

    /**
     * The default strategy: park the virtual thread.
     */
    static ApprovalStrategy virtualThread(ApprovalRegistry registry) {
        return new VirtualThreadApprovalStrategy(registry);
    }

    /**
     * Outcome of an approval request.
     */
    enum ApprovalOutcome {
        APPROVED,
        DENIED,
        TIMED_OUT
    }
}
