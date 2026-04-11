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

import java.time.Duration;
import java.time.Instant;
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

    private final ConcurrentHashMap<String, PendingEntry> pending = new ConcurrentHashMap<>();

    /**
     * Register a pending approval and return a future that will be completed
     * when the client responds.
     *
     * @param approval the pending approval details
     * @return a future that resolves to {@code true} (approved) or {@code false} (denied)
     */
    public CompletableFuture<Boolean> register(PendingApproval approval) {
        var future = new CompletableFuture<Boolean>();
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
        var action = path.substring(slashIdx + 1).trim().toLowerCase();

        var entry = pending.remove(approvalId);
        if (entry == null) {
            logger.debug("Approval {} not found in this registry (expired, already resolved, or owned by another session)", approvalId);
            return ResolveResult.UNKNOWN_ID;
        }

        var approved = "approve".equals(action) || "yes".equals(action);
        logger.debug("Approval {} resolved: {} (tool: {})",
                approvalId, approved ? "APPROVED" : "DENIED", entry.approval.toolName());
        entry.future.complete(approved);
        return ResolveResult.RESOLVED;
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
     * Wait for approval, blocking the current (virtual) thread.
     * The VT parks cheaply — no carrier thread is consumed.
     *
     * @param approval the pending approval
     * @param future   the future from {@link #register}
     * @return true if approved, false if denied
     * @throws ApprovalTimeoutException if the approval expires
     */
    public boolean awaitApproval(PendingApproval approval, CompletableFuture<Boolean> future) {
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

    private record PendingEntry(PendingApproval approval, CompletableFuture<Boolean> future) {}

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
