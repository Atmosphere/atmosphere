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

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gap #9 — exercise {@link ApprovalRegistry#resolve(String)} tri-state so a
 * cross-session approval ID submitted by a different session falls through as
 * {@link ApprovalRegistry.ResolveResult#UNKNOWN_ID} instead of silently
 * short-circuiting.
 *
 * <p>Each WebSocket connection gets its own {@link ApprovalRegistry}, mirroring
 * the production wiring where {@code AiStreamingSession} owns a session-scoped
 * registry. The handler stores per-resource state in a {@link ConcurrentHashMap}
 * keyed by resource UUID, matching commit {@code 0db97e3276}'s fix in
 * {@code AiStreamingSession.tryResolveApproval} and {@code AiPipeline
 * .tryResolveApproval}, both of which now short-circuit on
 * {@link ApprovalRegistry.ResolveResult#RESOLVED} only.
 *
 * <p>Wire protocol (line-based text on the WebSocket):
 * <ul>
 *   <li>{@code register} — create a pending approval in THIS session's
 *       registry and emit the generated approval id as metadata
 *       {@code hitl.cross.approvalId}. The registered future is intentionally
 *       parked until the session closes; the test does not wait on it.</li>
 *   <li>{@code /__approval/<id>/approve} or {@code /__approval/<id>/deny} —
 *       feed the ApprovalRegistry's real protocol through
 *       {@link ApprovalRegistry#resolve(String)}; emit the tri-state result as
 *       metadata {@code hitl.cross.resolveResult}.</li>
 *   <li>Any other value — fall through as if the handler received it from a
 *       user prompt and emit {@code hitl.cross.resolveResult=NOT_APPROVAL_MESSAGE}.</li>
 * </ul>
 */
public class HitlCrossSessionTestHandler implements AtmosphereHandler {

    private final ConcurrentHashMap<String, ApprovalRegistry> perResource = new ConcurrentHashMap<>();

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var line = reader.readLine();
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        var trimmed = line.trim();
        Thread.ofVirtual().name("hitl-cross").start(() -> handle(trimmed, resource));
    }

    private void handle(String message, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        var registry = perResource.computeIfAbsent(resource.uuid(), k -> new ApprovalRegistry());

        if ("register".equals(message)) {
            var id = ApprovalRegistry.generateId();
            var approval = new PendingApproval(
                    id, "cross_session_tool", Map.of("action", "noop"),
                    "Cross-session test approval", "conv-cross",
                    Instant.now().plusSeconds(60));
            // Register + discard the future — the approval deliberately never
            // resolves in this session; the test verifies a DIFFERENT session
            // submitting the same id does not resolve it either.
            registry.register(approval);
            session.sendMetadata("hitl.cross.approvalId", id);
            session.complete();
            return;
        }

        if (message.startsWith(ApprovalRegistry.APPROVAL_PREFIX)) {
            var result = registry.resolve(message);
            session.sendMetadata("hitl.cross.resolveResult", result.name());
            session.complete();
            return;
        }

        // Non-approval message — route through resolve() to prove the tri-state
        // returns NOT_APPROVAL_MESSAGE for plain input, which the caller uses
        // to decide whether to forward to the @Prompt pipeline.
        var result = registry.resolve(message);
        session.sendMetadata("hitl.cross.resolveResult", result.name());
        session.complete();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            perResource.remove(event.getResource().uuid());
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
        perResource.clear();
    }
}
