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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Registry for pending approval requests. Maps approval IDs to
 * {@link CompletableFuture}s that are completed when the client responds.
 *
 * <p>Thread-safe and VT-friendly (no {@code synchronized} blocks).</p>
 */
public final class ApprovalRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalRegistry.class);

    /** Approval response prefix used in the message protocol. */
    public static final String APPROVAL_PREFIX = "/__approval/";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ConcurrentHashMap<String, PendingEntry> pending = new ConcurrentHashMap<>();

    /**
     * Register a pending approval and return a future that will be completed
     * when the client responds.
     *
     * @param approval the pending approval details
     * @return a future that resolves to {@code true} (approved) or {@code false} (denied)
     */
    public CompletableFuture<Boolean> register(PendingApproval approval) {
        return registerForResolution(approval).thenApply(ApprovalResolution::approved);
    }

    /**
     * Register a pending approval and return a future that resolves to the rich
     * {@link ApprovalResolution} (decision + optional edited arguments / response
     * payload). The bare-boolean {@link #register(PendingApproval)} is a view over
     * this same future, so callers pick whichever shape they need.
     *
     * @param approval the pending approval details
     * @return a future completed when the client responds (or the approval is cancelled)
     */
    public CompletableFuture<ApprovalResolution> registerForResolution(PendingApproval approval) {
        var future = new CompletableFuture<ApprovalResolution>();
        pending.put(approval.approvalId(), new PendingEntry(approval, future));
        logger.debug("Registered pending approval: {} for tool {}",
                approval.approvalId(), approval.toolName());
        return future;
    }

    /**
     * Outcome of attempting to resolve an incoming message as an approval response.
     * Callers iterating across multiple registries (e.g. fallback routing) must
     * short-circuit only on {@link #RESOLVED} so a stale/unknown ID in one
     * registry does not swallow the message before a later registry that owns it
     * gets a chance.
     */
    public enum ResolveResult {
        /** Message did not match the approval protocol prefix at all. */
        NOT_APPROVAL_MESSAGE,
        /** Message was an approval protocol message but the ID is not in this registry. */
        UNKNOWN_ID,
        /** Message matched a pending approval in this registry and the future was completed. */
        RESOLVED
    }

    /**
     * Try to resolve an incoming message as an approval response.
     * Messages must match the format: {@code /__approval/<id>/approve} or {@code /__approval/<id>/deny}.
     *
     * @param message the incoming message
     * @return a {@link ResolveResult} describing what happened; callers iterating
     *     over multiple registries must short-circuit only on {@link ResolveResult#RESOLVED}
     */
    public ResolveResult resolve(String message) {
        if (message == null || !message.startsWith(APPROVAL_PREFIX)) {
            return ResolveResult.NOT_APPROVAL_MESSAGE;
        }

        var path = message.substring(APPROVAL_PREFIX.length());
        var slashIdx = path.indexOf('/');
        if (slashIdx < 0) {
            return ResolveResult.NOT_APPROVAL_MESSAGE;
        }

        var approvalId = path.substring(0, slashIdx);
        // The remainder is "<action>" optionally followed by whitespace and a
        // JSON (or free-form) payload, e.g.
        //   /__approval/<id>/approve {"arguments":{"limit":10}}
        //   /__approval/<id>/respond {"answer":"42"}
        var rest = path.substring(slashIdx + 1);
        var ws = firstWhitespace(rest);
        var action = (ws < 0 ? rest : rest.substring(0, ws)).trim().toLowerCase();
        var payload = ws < 0 ? "" : rest.substring(ws + 1).trim();

        var entry = pending.remove(approvalId);
        if (entry == null) {
            logger.debug("Approval {} not found in this registry (expired, already resolved, or owned by another session)", approvalId);
            return ResolveResult.UNKNOWN_ID;
        }

        var resolution = toResolution(action, payload, approvalId, entry.approval.toolName());
        logger.debug("Approval {} resolved: {} (tool: {})",
                approvalId, resolution.outcome(), entry.approval.toolName());
        entry.future.complete(resolution);
        return ResolveResult.RESOLVED;
    }

    /**
     * Map a wire {@code action} + optional {@code payload} to an
     * {@link ApprovalResolution}. Payload handling is fail-safe at the boundary:
     * a malformed edited-arguments payload <strong>denies</strong> rather than
     * running the tool with possibly-wrong arguments.
     */
    private static ApprovalResolution toResolution(String action, String payload,
                                                   String approvalId, String toolName) {
        switch (action) {
            case "approve", "yes" -> {
                if (payload.isEmpty()) {
                    return ApprovalResolution.approve();
                }
                var args = parseArguments(payload);
                if (args == null) {
                    logger.warn("Approval {} sent edited arguments that did not parse as a JSON "
                            + "object — denying tool {} (fail-safe)", approvalId, toolName);
                    return ApprovalResolution.deny();
                }
                return ApprovalResolution.approveWithArguments(args);
            }
            case "respond" -> {
                if (payload.isEmpty()) {
                    logger.warn("Approval {} 'respond' with no payload — denying tool {} (fail-safe)",
                            approvalId, toolName);
                    return ApprovalResolution.deny();
                }
                return ApprovalResolution.respond(parseResponse(payload));
            }
            default -> {
                return ApprovalResolution.deny();
            }
        }
    }

    /**
     * Parse an edited-arguments payload. Accepts either a bare JSON object
     * ({@code {...}}) or {@code {"arguments": {...}}}. Returns {@code null} when
     * the payload is not a JSON object (fail-safe signal to the caller).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(String payload) {
        try {
            var node = JSON.readTree(payload);
            if (!node.isObject()) {
                return null;
            }
            var args = node.has("arguments") ? node.get("arguments") : node;
            if (!args.isObject()) {
                return null;
            }
            return (Map<String, Object>) JSON.treeToValue(args, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Parse a free-form / structured respond payload. A valid JSON value is
     * returned as the parsed object (Map / List / scalar); anything else is
     * returned verbatim as the free-form string the reviewer typed.
     */
    private static Object parseResponse(String payload) {
        try {
            var node = JSON.readTree(payload);
            return JSON.treeToValue(node, Object.class);
        } catch (RuntimeException e) {
            return payload;
        }
    }

    private static int firstWhitespace(String s) {
        for (var i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Single-registry adapter returning {@code true} iff the message was an approval
     * protocol message (whether or not this registry owned the ID). Callers use this
     * to short-circuit forwarding the message to the runtime as a user prompt.
     *
     * <p><b>Do not use this from a cross-registry fallback loop.</b> Use {@link #resolve(String)}
     * directly and only stop iterating on {@link ResolveResult#RESOLVED}, otherwise the
     * first registry will consume the message even when a later registry owns the
     * pending approval.</p>
     */
    public boolean tryResolve(String message) {
        var r = resolve(message);
        return r == ResolveResult.RESOLVED || r == ResolveResult.UNKNOWN_ID;
    }

    /**
     * Cancel every pending approval by completing each future with {@code false}
     * (denied). Called by {@link org.atmosphere.ai.AiStreamingSession#cancelInflight}
     * when the client transport disconnects so parked virtual threads waiting
     * on {@link #awaitApproval} unblock immediately instead of waiting out the
     * per-approval timeout (default 5 min) on a now-orphaned connection.
     *
     * <p>Closes Correctness Invariant #2 (Terminal Path Completeness) for the
     * approval-blocked VT case — every disconnect path now releases the lock
     * synchronously rather than parking the VT until expiry.</p>
     *
     * <p>The upstream LLM HTTP call is aborted separately by
     * {@link org.atmosphere.ai.ExecutionHandle#cancel} before this is called,
     * so the "denied" tool result lands in a runtime that has already begun
     * to unwind — no further LLM I/O is issued.</p>
     *
     * @return the number of approvals that were pending and have now been resolved
     */
    public int cancelAllPending() {
        int cancelled = 0;
        var it = pending.values().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            it.remove();
            entry.future.complete(ApprovalResolution.deny());
            cancelled++;
        }
        if (cancelled > 0) {
            logger.debug("cancelAllPending cancelled {} pending approval(s)", cancelled);
        }
        return cancelled;
    }

    /**
     * Wait for approval, blocking the current (virtual) thread.
     * The VT parks cheaply — no carrier thread is consumed.
     *
     * @param approval the pending approval
     * @param future   the future from {@link #register}
     * @return true if approved, false if denied
     * @throws ApprovalTimeoutException if the approval expires
     */
    public boolean awaitApproval(PendingApproval approval, CompletableFuture<Boolean> future) {
        return await(approval, future);
    }

    /**
     * Wait for the rich {@link ApprovalResolution}, blocking the current
     * (virtual) thread. Same parking semantics and timeout handling as
     * {@link #awaitApproval}, but returns the reviewer's edited arguments /
     * response payload alongside the decision.
     *
     * @throws ApprovalTimeoutException if the approval expires
     */
    public ApprovalResolution awaitResolution(PendingApproval approval,
                                              CompletableFuture<ApprovalResolution> future) {
        return await(approval, future);
    }

    private <T> T await(PendingApproval approval, CompletableFuture<T> future) {
        var timeout = Duration.between(Instant.now(), approval.expiresAt());
        if (timeout.isNegative() || timeout.isZero()) {
            pending.remove(approval.approvalId());
            throw new ApprovalTimeoutException(approval);
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(approval.approvalId());
            throw new ApprovalTimeoutException(approval);
        } catch (Exception e) {
            pending.remove(approval.approvalId());
            throw new RuntimeException("Approval wait interrupted", e);
        }
    }

    /**
     * Generate a unique approval ID.
     */
    public static String generateId() {
        return "apr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * Check if a message looks like an approval response (for fast-path filtering).
     */
    public static boolean isApprovalMessage(String message) {
        return message != null && message.startsWith(APPROVAL_PREFIX);
    }

    private record PendingEntry(PendingApproval approval,
                                CompletableFuture<ApprovalResolution> future) {}

    /**
     * Thrown when an approval request times out.
     */
    public static class ApprovalTimeoutException extends org.atmosphere.ai.AiException {
        private final PendingApproval approval;

        public ApprovalTimeoutException(PendingApproval approval) {
            super("Approval timed out for tool '" + approval.toolName() + "' (id: " + approval.approvalId() + ")");
            this.approval = approval;
        }

        public PendingApproval approval() {
            return approval;
        }
    }
}
