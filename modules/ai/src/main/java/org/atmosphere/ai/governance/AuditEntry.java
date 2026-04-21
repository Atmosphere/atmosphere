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
package org.atmosphere.ai.governance;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured audit record of a single {@link GovernancePolicy#evaluate} call.
 * Mirrors the {@code audit_entry} shape MS Agent Governance Toolkit emits,
 * with one addition: {@link #evaluationMs} for wire-level latency tracking
 * on the decision path (same field surfaced on {@code /api/admin/governance/check}).
 *
 * <h2>Context snapshot</h2>
 * {@link #contextSnapshot} is a redaction-safe projection of the
 * {@link PolicyContext} at evaluate-time: phase, a preview of the message
 * (truncated to 200 chars), model, user/session/agent/conversation ids,
 * and every metadata entry whose value is a string, boolean, or primitive
 * number. Complex metadata values are coerced to their {@code toString()}
 * shape or elided to avoid serialization surprises in the admin surface.
 *
 * <h2>Retention</h2>
 * Entries are ring-buffered by {@link GovernanceDecisionLog} with an
 * operator-controlled capacity (defaults to 500). This is a rolling window
 * for post-incident triage, not a long-term audit log — persistent audit
 * remains the operator's responsibility (ship to Kafka, write to Postgres,
 * etc. via a custom {@link GovernanceDecisionLog} listener in a follow-up).
 */
public record AuditEntry(
        Instant timestamp,
        String policyName,
        String policySource,
        String policyVersion,
        String decision,           // "admit" | "transform" | "deny"
        String reason,             // empty on admit; non-empty on transform / deny
        Map<String, Object> contextSnapshot,
        double evaluationMs) {

    public AuditEntry {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        policyName = policyName == null ? "unknown" : policyName;
        policySource = policySource == null ? "" : policySource;
        policyVersion = policyVersion == null ? "" : policyVersion;
        decision = decision == null ? "admit" : decision;
        reason = reason == null ? "" : reason;
        contextSnapshot = contextSnapshot == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(contextSnapshot));
    }
}
