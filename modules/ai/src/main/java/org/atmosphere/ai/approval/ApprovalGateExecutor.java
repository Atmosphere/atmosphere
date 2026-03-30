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
import org.atmosphere.ai.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Wraps a {@link ToolExecutor} with an approval gate. When executed, it
 * pauses (parks the virtual thread) until the client approves or denies.
 *
 * <p>This wrapper is applied at tool registration time when
 * {@code @RequiresApproval} is present, so ALL runtimes (Built-in,
 * LangChain4j, Spring AI, ADK) automatically get approval gates.</p>
 */
public class ApprovalGateExecutor implements ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalGateExecutor.class);

    private final ToolExecutor delegate;
    private final String toolName;
    private final String approvalMessage;
    private final long timeoutSeconds;
    private final ApprovalStrategy strategy;
    private final StreamingSession session;

    public ApprovalGateExecutor(ToolExecutor delegate, String toolName,
                                String approvalMessage, long timeoutSeconds,
                                ApprovalStrategy strategy, StreamingSession session) {
        this.delegate = delegate;
        this.toolName = toolName;
        this.approvalMessage = approvalMessage;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 300;
        this.strategy = strategy;
        this.session = session;
    }

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        var approval = new PendingApproval(
                ApprovalRegistry.generateId(),
                toolName,
                arguments,
                approvalMessage,
                session.sessionId(),
                Instant.now().plusSeconds(timeoutSeconds)
        );

        var outcome = strategy.awaitApproval(approval, session);
        return switch (outcome) {
            case APPROVED -> {
                logger.info("Tool {} approved, executing", toolName);
                yield delegate.execute(arguments);
            }
            case DENIED -> {
                logger.info("Tool {} denied by user", toolName);
                yield Map.of("status", "cancelled", "message", "Action cancelled by user");
            }
            case TIMED_OUT -> {
                logger.info("Tool {} approval timed out", toolName);
                yield Map.of("status", "timeout", "message", "Approval timed out");
            }
        };
    }
}
