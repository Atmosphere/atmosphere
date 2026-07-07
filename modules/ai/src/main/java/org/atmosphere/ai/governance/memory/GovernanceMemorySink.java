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
package org.atmosphere.ai.governance.memory;

import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.AuditSink;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernanceGuidance;
import org.atmosphere.ai.memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Opt-in {@link AuditSink} that persists feedback-eligible governance decisions
 * ({@code deny} / {@code prefer}) to a {@link LongTermMemory} store, so the guidance
 * survives a restart and outlives the {@link GovernanceDecisionLog} ring buffer. The
 * durable counterpart to the ephemeral feedback loop: register this sink (default off) to
 * make governance lessons durable; leave it unregistered to keep the ephemeral,
 * zero-persistence default.
 *
 * <p>Each decision is rendered by {@link GovernanceGuidance} (the same renderer the
 * ephemeral path uses — Correctness Invariant #7, mode parity), wrapped in a
 * {@link GovernanceFact} provenance envelope (policy identity, confidence, TTL), and stored
 * under a reserved, per-user namespace key so it never mixes with ordinary user facts.
 * Durable guidance is therefore scoped by {@code user_id} (long-term memory's key) — coarser
 * than the ephemeral path's conversation/session scope, which is the right grain for
 * "this user has repeatedly been advised X."</p>
 *
 * <h2>Ownership &amp; back-pressure</h2>
 * The sink does <b>not</b> own the store it writes to (Correctness Invariant #1) — {@link #close()}
 * is a no-op and never closes the delegate. {@link #write} runs synchronously on the admission
 * thread; when the store performs blocking IO (SQLite/Redis), wrap this sink in
 * {@code AsyncAuditSink} so a slow write cannot back-pressure admission.
 */
public final class GovernanceMemorySink implements AuditSink {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceMemorySink.class);

    /** Reserved namespace prefix under which durable governance guidance is keyed, per user. */
    public static final String NAMESPACE_PREFIX = "atmo-gov:";

    /** Default confidence stamped on a persisted decision (a real deny/prefer is authoritative). */
    public static final double DEFAULT_CONFIDENCE = 1.0;

    private final LongTermMemory store;
    private final Duration ttl;
    private final double confidence;
    private final Clock clock;

    /**
     * @param store      the long-term memory to persist into (should be wrapped in
     *                   {@link GovernanceProvenanceMemory} so the read gate applies)
     * @param ttl        how long a persisted lesson stays valid, or {@code null} for no expiry
     * @param confidence 0.0–1.0 confidence stamped on each lesson
     * @param clock      clock for computing expiry (null → system UTC)
     */
    public GovernanceMemorySink(LongTermMemory store, Duration ttl, double confidence, Clock clock) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl must be positive or null, got: " + ttl);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
        }
        this.store = store;
        this.ttl = ttl;
        this.confidence = confidence;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /** Namespace key under which a subject's durable guidance is stored. */
    public static String namespaceKey(String userId) {
        return NAMESPACE_PREFIX + userId;
    }

    @Override
    public void write(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        var decision = entry.decision();
        if (!"deny".equals(decision) && !"prefer".equals(decision)) {
            return;                                  // only feedback-eligible decisions are durable
        }
        // Defense against re-entrancy: the memory decorators record their own drops/screens
        // as "deny" entries. Never persist those (their snapshots use "user", not "user_id",
        // so they would be skipped below anyway — this is belt-and-suspenders).
        if ("governance-provenance".equals(entry.policyName())
                || "memory-safety".equals(entry.policyName())) {
            return;
        }
        var userId = asText(entry.contextSnapshot().get("user_id"));
        if (userId.isBlank()) {
            return;                                  // cannot scope durably without a user
        }
        var preferred = asText(entry.contextSnapshot().get(GovernanceDecisionLog.PREFERRED_KEY));
        var line = GovernanceGuidance.line(decision, entry.reason(), preferred);
        if (line == null) {
            return;
        }
        var expiresAt = ttl == null ? null : clock.instant().plus(ttl);
        var fact = GovernanceFact.encode(entry.policyName(), confidence, expiresAt, line);
        try {
            store.saveFact(namespaceKey(userId), fact);
        } catch (RuntimeException e) {
            // Isolated like any AuditSink failure — durable persistence is best-effort and must
            // not break admission (the ephemeral loop still carries the signal this turn).
            logger.warn("Failed to persist governance guidance for user {}: {}", userId, e.toString());
        }
    }

    @Override
    public String name() {
        return "governance-memory";
    }

    private static String asText(Object value) {
        return value == null ? "" : value.toString().strip();
    }
}
