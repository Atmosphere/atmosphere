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

import java.time.Instant;
import java.util.Map;

/**
 * A tool execution waiting for human approval.
 *
 * @param approvalId     unique identifier for this approval request
 * @param toolName       the tool that requires approval
 * @param arguments      the arguments the LLM wants to pass to the tool
 * @param message        the approval prompt shown to the user
 * @param conversationId the conversation this approval belongs to
 * @param expiresAt      when this approval request expires
 */
public record PendingApproval(
        String approvalId,
        String toolName,
        Map<String, Object> arguments,
        String message,
        String conversationId,
        Instant expiresAt
) {
    public PendingApproval {
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
