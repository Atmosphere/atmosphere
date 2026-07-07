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

import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceProvenanceMemoryTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_000_000L);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void cleanup() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void survivorReturnedWithMarkerStripped() {
        var delegate = new InMemoryLongTermMemory();
        var future = NOW.plusSeconds(3600);
        delegate.saveFact("u", GovernanceFact.encode("least-privilege", 1.0, future, "Prefer: scoped"));

        var gated = new GovernanceProvenanceMemory(delegate, 0.0, clock);
        var facts = gated.getFacts("u", 10);

        assertEquals(List.of("Prefer: scoped"), facts, "marker stripped, guidance returned clean");
    }

    @Test
    void expiredLessonDropped() {
        var delegate = new InMemoryLongTermMemory();
        delegate.saveFact("u", GovernanceFact.encode("p", 1.0, NOW.minusSeconds(1), "stale"));
        delegate.saveFact("u", GovernanceFact.encode("p", 1.0, NOW.plusSeconds(60), "fresh"));

        var gated = new GovernanceProvenanceMemory(delegate, 0.0, clock);
        var facts = gated.getFacts("u", 10);

        assertTrue(facts.contains("fresh"));
        assertFalse(facts.contains("stale"), "expired governance lesson must be dropped on read");
    }

    @Test
    void lowConfidenceLessonDropped() {
        var delegate = new InMemoryLongTermMemory();
        delegate.saveFact("u", GovernanceFact.encode("p", 0.3, null, "shaky"));
        delegate.saveFact("u", GovernanceFact.encode("p", 0.9, null, "solid"));

        var gated = new GovernanceProvenanceMemory(delegate, 0.5, clock);
        var facts = gated.getFacts("u", 10);

        assertTrue(facts.contains("solid"));
        assertFalse(facts.contains("shaky"), "below-confidence lesson dropped");
    }

    @Test
    void ordinaryUserFactsPassThroughUntouched() {
        var delegate = new InMemoryLongTermMemory();
        delegate.saveFact("u", "Has a dog named Max");

        var gated = new GovernanceProvenanceMemory(delegate, 0.0, clock);
        assertEquals(List.of("Has a dog named Max"), gated.getFacts("u", 10));
    }

    @Test
    void writePathIsPassThrough() {
        var delegate = new InMemoryLongTermMemory();
        var gated = new GovernanceProvenanceMemory(delegate, 0.0, clock);
        gated.saveFact("u", "plain fact");
        assertEquals(List.of("plain fact"), delegate.getFacts("u", 10),
                "writes delegate unchanged (facts arrive already encoded from the sink)");
    }

    @Test
    void dropIsRecordedToDecisionLog() {
        GovernanceDecisionLog.install(50);
        var delegate = new InMemoryLongTermMemory();
        delegate.saveFact("u", GovernanceFact.encode("p", 1.0, NOW.minusSeconds(1), "stale"));

        new GovernanceProvenanceMemory(delegate, 0.0, clock).getFacts("u", 10);

        var recorded = GovernanceDecisionLog.installed().recent(10);
        assertTrue(recorded.stream().anyMatch(e -> "governance-provenance".equals(e.policyName())),
                "an expired-lesson drop must be auditable in the decision log");
    }
}
