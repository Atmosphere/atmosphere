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
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ApprovalStrategy.ApprovalOutcome;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test handler for the real HITL approval lifecycle (Gap 9 follow-up).
 *
 * <p>Prompt {@code "approve-flow"} → emits a {@link AiEvent.ToolStart} bookend,
 * drives the real {@link ApprovalStrategy#virtualThread(ApprovalRegistry)} which
 * emits {@link AiEvent.ApprovalRequired} and parks the virtual thread on the
 * real {@link ApprovalRegistry}, then emits {@link AiEvent.ToolResult} and
 * {@link AiEvent.Complete} after the approval resolves.</p>
 *
 * <p>Prompt {@code "deny-flow"} → same set-up, but the handler expects the
 * registry to complete the future as {@code false} and emits
 * {@code hitl.denied=true} metadata before completing — no {@code tool-result}
 * frame is emitted on the denied path.</p>
 *
 * <p><b>Wire protocol (preserved for backward compatibility with the existing
 * {@code ai-hitl-real-flow.spec.ts} e2e spec).</b> Clients send one of:</p>
 * <ul>
 *   <li>{@code approve-flow} / {@code deny-flow} — start a new approval
 *       exercise</li>
 *   <li>{@code approve:<approvalId>} / {@code deny:<approvalId>} — the legacy
 *       bespoke shorthand; the handler translates these to the canonical
 *       {@link ApprovalRegistry#APPROVAL_PREFIX}{@code <id>/<action>} wire
 *       format before routing through {@link ApprovalRegistry#resolve(String)}.</li>
 *   <li>{@code /__approval/<id>/approve} or {@code /__approval/<id>/deny} —
 *       the canonical production format, accepted as-is.</li>
 * </ul>
 *
 * <p>Ownership: every {@link AtmosphereResource} gets its own
 * {@link ApprovalRegistry}, mirroring production where
 * {@code AiStreamingSession} scopes one registry per session. The process-wide
 * static {@code HashMap} from the pre-Gap-9 implementation — which bypassed the
 * real {@code ApprovalRegistry} SPI entirely — is gone; see
 * {@link HitlCrossSessionTestHandler} for the same per-connection pattern used
 * to regression-test {@link ApprovalRegistry.ResolveResult#UNKNOWN_ID}.</p>
 *
 * <p>Policy + fail-closed: the handler evaluates a {@link ToolApprovalPolicy}
 * (defaulting to {@link ToolApprovalPolicy#annotated()}) before driving the
 * strategy, and fails closed when the policy demands approval but no
 * {@link ApprovalStrategy} is wired — matching the
 * {@code ToolExecutionHelper.executeWithApproval} contract locked in by commit
 * {@code 56b1046f6f}.</p>
 */
public class HitlApprovalTestHandler implements AtmosphereHandler {

    private static final String APPROVE_FLOW = "approve-flow";
    private static final String DENY_FLOW = "deny-flow";
    private static final String TOOL_NAME = "dangerous_tool";
    private static final Map<String, Object> TOOL_ARGS = Map.of("action", "delete_all");
    private static final String APPROVAL_MESSAGE = "Confirm dangerous operation?";
    private static final long APPROVAL_TIMEOUT_SECONDS = 30L;

    /** Real tool definition — annotation-driven policy recognises this as approval-required. */
    private static final ToolDefinition DANGEROUS_TOOL = ToolDefinition.builder(
                    TOOL_NAME, "Deletes all data — requires human approval.")
            .executor(args -> Map.of("status", "deleted"))
            .requiresApproval(APPROVAL_MESSAGE, APPROVAL_TIMEOUT_SECONDS)
            .build();

    /**
     * Registry scope: one {@link ApprovalRegistry} per {@link AtmosphereResource},
     * keyed by {@code resource.uuid()}. A new WebSocket connection owns its own
     * registry exactly like {@code AiStreamingSession} does in production.
     */
    private final ConcurrentHashMap<String, ApprovalRegistry> perResource =
            new ConcurrentHashMap<>();

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var message = reader.readLine();
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        var trimmed = message.trim();
        var registry = perResource.computeIfAbsent(resource.uuid(), k -> new ApprovalRegistry());

        // Decision messages — legacy bespoke shorthand and canonical wire format
        // both funnel through ApprovalRegistry.resolve(String) via the canonical
        // /__approval/<id>/<action> protocol.
        if (trimmed.startsWith("approve:") || trimmed.startsWith("deny:")) {
            routeLegacyDecision(registry, trimmed);
            return;
        }
        if (trimmed.startsWith(ApprovalRegistry.APPROVAL_PREFIX)) {
            registry.resolve(trimmed);
            return;
        }

        // Flow messages — kick off a real approval lifecycle on a VT so we do
        // not block the Atmosphere request thread while the ApprovalStrategy
        // parks waiting on the registry's CompletableFuture.
        Thread.ofVirtual().name("hitl-test").start(() ->
                handlePrompt(trimmed, resource, registry));
    }

    private void handlePrompt(String prompt, AtmosphereResource resource,
                               ApprovalRegistry registry) {
        var session = StreamingSessions.start(resource);
        session.sendMetadata("hitl.prompt", prompt);

        if (!APPROVE_FLOW.equals(prompt) && !DENY_FLOW.equals(prompt)) {
            session.sendMetadata("hitl.unknown", prompt);
            session.complete();
            return;
        }

        // Emit the tool-start bookend so the wire stream shape matches what
        // real runtime bridges produce around a gated tool invocation.
        session.emit(new AiEvent.ToolStart(TOOL_NAME, TOOL_ARGS));

        // Evaluate the policy — mirrors ToolExecutionHelper.executeWithApproval
        // so the test handler exercises the real production gating logic.
        var policy = ToolApprovalPolicy.annotated();
        var requiresApproval = policy.requiresApproval(DANGEROUS_TOOL);

        if (!requiresApproval) {
            session.emit(new AiEvent.ToolResult(TOOL_NAME, Map.of("status", "deleted")));
            session.emit(new AiEvent.Complete(null, Map.of()));
            return;
        }

        // Deny-by-policy fast path — no strategy invocation, no registry entry.
        if (policy instanceof ToolApprovalPolicy.DenyAll) {
            session.sendMetadata("hitl.denied", true);
            session.emit(new AiEvent.Complete(null, Map.of()));
            return;
        }

        // Fail-closed: if we ever wire a null strategy alongside a policy that
        // demands approval, execution must NOT proceed. Matches the invariant
        // established in ToolExecutionHelper.executeWithApproval (commit
        // 56b1046f6f) — the null-strategy branch short-circuits the tool.
        ApprovalStrategy strategy = ApprovalStrategy.virtualThread(registry);
        if (strategy == null) {
            session.sendMetadata("hitl.failClosed", true);
            session.emit(new AiEvent.Complete(null, Map.of()));
            return;
        }

        // Drive the real ApprovalStrategy. virtualThread(registry) registers the
        // pending approval, emits AiEvent.ApprovalRequired via session.emit, and
        // parks the VT on the registry's CompletableFuture.
        var approval = new PendingApproval(
                ApprovalRegistry.generateId(),
                TOOL_NAME,
                TOOL_ARGS,
                APPROVAL_MESSAGE,
                session.sessionId(),
                Instant.now().plusSeconds(APPROVAL_TIMEOUT_SECONDS));

        ApprovalOutcome outcome;
        try {
            outcome = strategy.awaitApproval(approval, session);
        } catch (RuntimeException e) {
            session.emit(new AiEvent.Error("Approval wait failed: " + e.getMessage(),
                    "approval_error", false));
            return;
        }

        switch (outcome) {
            case APPROVED -> {
                session.emit(new AiEvent.ToolResult(TOOL_NAME, Map.of("status", "deleted")));
                session.emit(new AiEvent.TextDelta("Operation completed successfully."));
                session.emit(new AiEvent.Complete(null, Map.of()));
            }
            case DENIED -> {
                session.sendMetadata("hitl.denied", true);
                session.emit(new AiEvent.Complete(null, Map.of()));
            }
            case TIMED_OUT -> {
                session.emit(new AiEvent.Error("Approval timed out",
                        "approval_timeout", false));
            }
        }
    }

    /**
     * Translate {@code approve:<id>} / {@code deny:<id>} into the canonical
     * {@code /__approval/<id>/<action>} wire format, then route through
     * {@link ApprovalRegistry#resolve(String)}. We intentionally do NOT look up
     * the pending entry ourselves — {@code resolve()} owns the tri-state and
     * future completion, which is the whole point of Gap #9.
     */
    private void routeLegacyDecision(ApprovalRegistry registry, String message) {
        var colonIdx = message.indexOf(':');
        if (colonIdx < 0) {
            return;
        }
        var action = message.substring(0, colonIdx);
        var id = message.substring(colonIdx + 1).trim();
        if (id.isEmpty()) {
            return;
        }
        var canonical = ApprovalRegistry.APPROVAL_PREFIX + id + "/" + action;
        registry.resolve(canonical);
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
