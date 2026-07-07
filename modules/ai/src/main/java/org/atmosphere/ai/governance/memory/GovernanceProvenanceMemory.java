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
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Decorator that gates governance-derived facts (those carrying the {@link GovernanceFact}
 * provenance marker) on the <em>read</em> path: an expired or below-confidence lesson is
 * dropped before it can be re-injected, and a surviving lesson is returned with its marker
 * stripped (the clean guidance line). Ordinary user facts — anything without the marker —
 * pass through untouched.
 *
 * <p>This is the durable counterpart to the ephemeral feedback loop and the answer to the
 * article's "supervision of learned lessons" open problem: a wrong governance lesson written
 * to memory cannot compound forever because it either lapses (TTL) or is filtered below a
 * confidence floor. The expiry gate is always on; {@code minConfidence} is an additional
 * operator lever (default {@code 0.0} — a real deny/prefer decision is written at full
 * confidence, so expiry is the primary gate).</p>
 *
 * <p>Symmetric to {@link ScreenedLongTermMemory} (which screens the write path for injection):
 * both are {@link LongTermMemory} decorators that record enforcement events to the installed
 * {@link GovernanceDecisionLog}, and they compose — wrap with both to screen writes and gate
 * governance reads.</p>
 */
public final class GovernanceProvenanceMemory implements LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceProvenanceMemory.class);

    private final LongTermMemory delegate;
    private final double minConfidence;
    private final Clock clock;

    public GovernanceProvenanceMemory(LongTermMemory delegate, double minConfidence, Clock clock) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate LongTermMemory must not be null");
        }
        if (minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "minConfidence must be in [0.0, 1.0], got: " + minConfidence);
        }
        this.delegate = delegate;
        this.minConfidence = minConfidence;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /** Confidence floor below which governance lessons are dropped on read. */
    public double minConfidence() {
        return minConfidence;
    }

    // --- write path: pass-through (facts arrive already encoded from the sink) ------------

    @Override
    public void saveFact(String userId, String fact) {
        delegate.saveFact(userId, fact);
    }

    @Override
    public void saveFacts(String userId, List<String> facts) {
        delegate.saveFacts(userId, facts);
    }

    @Override
    public void replaceFacts(String userId, List<String> facts) {
        delegate.replaceFacts(userId, facts);
    }

    @Override
    public void clear(String userId) {
        delegate.clear(userId);
    }

    @Override
    public int factCount(String userId) {
        return delegate.factCount(userId);
    }

    // --- read path: gate governance facts -------------------------------------------------

    @Override
    public List<String> getFacts(String userId, int maxFacts) {
        var raw = delegate.getFacts(userId, maxFacts);
        if (raw.isEmpty()) {
            return raw;
        }
        var now = clock.instant();
        var out = new ArrayList<String>(raw.size());
        for (var fact : raw) {
            var parsed = GovernanceFact.parse(fact);
            if (parsed.isEmpty()) {
                out.add(fact);                       // ordinary user fact — untouched
                continue;
            }
            var gov = parsed.get();
            if (gov.isExpired(now)) {
                recordDrop(userId, gov, "expired", now);
                continue;
            }
            if (gov.confidence() < minConfidence) {
                recordDrop(userId, gov, "low-confidence", now);
                continue;
            }
            out.add(gov.text());                     // survivor — marker stripped
        }
        return List.copyOf(out);
    }

    private void recordDrop(String userId, GovernanceFact gov, String why, Instant now) {
        if (logger.isDebugEnabled()) {
            logger.debug("Governance-provenance gate dropped {} lesson from policy '{}' for user {}",
                    why, gov.policy(), userId);
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("phase", "memory_read");
        snapshot.put("user", userId);
        snapshot.put("policy", gov.policy());
        snapshot.put("confidence", gov.confidence());
        snapshot.put("gate", why);
        if (gov.expiresAt() != null) {
            snapshot.put("expired_at", gov.expiresAt().getEpochSecond());
        }
        var entry = new AuditEntry(now, "governance-provenance",
                "code:" + getClass().getSimpleName(), "1.0",
                "deny", why + " governance lesson dropped on read", snapshot, 0.0);
        GovernanceDecisionLog.installed().record(entry);
    }
}
