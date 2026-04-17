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
package org.atmosphere.ai.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Per-user identity, permissions, and audit surface. Consolidates the
 * fragments previously scattered across {@code AiConfig.LlmSettings}
 * (global credentials), {@code StreamingTextBudgetManager} (global token
 * budget), and {@code CoordinationJournal} (audit data without per-user
 * endpoint) into a single named primitive.
 *
 * <h2>Composition</h2>
 *
 * {@code AgentIdentity} delegates storage concerns to smaller SPIs so each
 * can evolve independently:
 *
 * <ul>
 *   <li>{@link CredentialStore} owns secret storage</li>
 *   <li>{@link PermissionMode} captures session-scoped tool authorization</li>
 *   <li>Audit events are surfaced through {@link #audit(String, int)} and
 *       recorded through {@link #recordAudit(AuditEvent)}</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 *
 * Default deny (Correctness Invariant #6). {@link #permissionMode(String)}
 * returns {@link PermissionMode#DEFAULT} for an unknown user — per-tool
 * {@code @RequiresApproval} gates are still enforced on top of the mode.
 */
public interface AgentIdentity {

    /** Current session permission mode for a user. */
    PermissionMode permissionMode(String userId);

    /** Update the permission mode for a user. */
    void setPermissionMode(String userId, PermissionMode mode);

    /** Credential store backing per-user secrets. */
    CredentialStore credentialStore();

    /**
     * Record an audit event. Called from tool execution, HITL approval,
     * gateway admission, and any other user-attributable action.
     */
    void recordAudit(AuditEvent event);

    /**
     * Retrieve the most recent audit events for a user, newest first.
     *
     * @param userId the user to query
     * @param limit  maximum number of entries to return
     */
    List<AuditEvent> audit(String userId, int limit);

    /**
     * Create a read-only share for a user's active session, returning a
     * share token that admin endpoints surface to others with view-only
     * access to the session transcript.
     *
     * @param userId    owner of the session
     * @param sessionId the session to share
     * @param ttl       how long the share remains valid
     */
    SessionShare createShare(String userId, String sessionId, java.time.Duration ttl);

    /** Revoke an existing share by its token. No-op if unknown. */
    void revokeShare(String shareToken);

    /** Look up an active share by token. Empty if expired or revoked. */
    Optional<SessionShare> lookupShare(String shareToken);

    /**
     * A single auditable action. Stored with a monotonic identifier and a
     * wall-clock timestamp.
     *
     * @param id        stable identifier assigned on store
     * @param userId    user responsible for the action
     * @param action    verb + noun ({@code "tool.call"}, {@code "memory.read"},
     *                  {@code "gateway.rejected"})
     * @param detail    free-form human-readable detail
     * @param timestamp when the event was recorded
     */
    record AuditEvent(String id, String userId, String action, String detail, Instant timestamp) {
    }

    /**
     * A read-only share of a session transcript. The token is a URL-safe
     * opaque string; share consumers never see the underlying session id
     * directly.
     *
     * @param token       opaque share token
     * @param userId      owner of the shared session
     * @param sessionId   session being shared
     * @param createdAt   when the share was minted
     * @param expiresAt   when the share expires
     */
    record SessionShare(
            String token,
            String userId,
            String sessionId,
            Instant createdAt,
            Instant expiresAt) {

        public boolean isActive(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
