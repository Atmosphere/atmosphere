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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test handler for the real HITL approval lifecycle (Gap 9).
 *
 * <p>Prompt "approve-flow" → emits ApprovalRequired, waits for approval
 * message, then emits ToolResult + Complete.</p>
 * <p>Prompt "deny-flow" → same, but expects denial message and completes
 * cleanly without ToolResult.</p>
 *
 * <p>Approval/denial arrives as a subsequent WebSocket message matching
 * "approve:&lt;id&gt;" or "deny:&lt;id&gt;".</p>
 */
public class HitlApprovalTestHandler implements AtmosphereHandler {

    private static final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var message = reader.readLine();
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        var trimmed = message.trim();

        // Check if this is an approval/denial response
        if (trimmed.startsWith("approve:") || trimmed.startsWith("deny:")) {
            handleDecision(trimmed);
            return;
        }

        Thread.ofVirtual().name("hitl-test").start(() ->
                handlePrompt(trimmed, resource));
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        session.sendMetadata("hitl.prompt", prompt);
        var approvalId = "apr_" + session.sessionId().substring(0, 8);

        session.emit(new AiEvent.ToolStart("dangerous_tool",
                Map.of("action", "delete_all")));

        session.emit(new AiEvent.ApprovalRequired(
                approvalId, "dangerous_tool",
                Map.of("action", "delete_all"),
                "Confirm dangerous operation?", 30));

        var latch = new CountDownLatch(1);
        var approval = new PendingApproval(session, latch);
        pending.put(approvalId, approval);

        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                session.emit(new AiEvent.Error("Approval timed out",
                        "approval_timeout", false));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
            return;
        } finally {
            pending.remove(approvalId);
        }

        if (approval.approved) {
            session.emit(new AiEvent.ToolResult("dangerous_tool",
                    Map.of("status", "deleted")));
            session.emit(new AiEvent.TextDelta("Operation completed successfully."));
        } else {
            session.sendMetadata("hitl.denied", true);
        }
        session.emit(new AiEvent.Complete(null, Map.of()));
    }

    private void handleDecision(String message) {
        var parts = message.split(":", 2);
        var action = parts[0];
        var id = parts[1];
        var approval = pending.get(id);
        if (approval != null) {
            approval.approved = "approve".equals(action);
            approval.latch.countDown();
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
        pending.clear();
    }

    private static class PendingApproval {
        final StreamingSession session;
        final CountDownLatch latch;
        volatile boolean approved;

        PendingApproval(StreamingSession session, CountDownLatch latch) {
            this.session = session;
            this.latch = latch;
        }
    }
}
