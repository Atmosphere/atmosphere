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
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceMemorySinkTest {

    private static final Instant NOW = Instant.ofEpochSecond(2_000_000L);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static AuditEntry entry(String decision, String reason, Map<String, Object> snapshot) {
        return new AuditEntry(Instant.now(), "least-privilege", "yaml:test", "1",
                decision, reason, snapshot, 0.3);
    }

    @Test
    void persistsDenyUnderUserNamespaceWithProvenance() {
        var store = new InMemoryLongTermMemory();
        var sink = new GovernanceMemorySink(store, Duration.ofHours(1), 1.0, clock);

        sink.write(entry("deny", "destructive action is never allowed",
                Map.of("user_id", "user-1")));

        var facts = store.getFacts(GovernanceMemorySink.namespaceKey("user-1"), 10);
        assertEquals(1, facts.size());
        var gov = GovernanceFact.parse(facts.get(0)).orElseThrow();
        assertEquals("least-privilege", gov.policy());
        assertEquals(NOW.plus(Duration.ofHours(1)), gov.expiresAt(), "TTL stamped from the clock");
        assertTrue(gov.text().contains("was denied"), gov.text());
        assertTrue(gov.text().contains("destructive action is never allowed"), gov.text());
    }

    @Test
    void persistsPreferWithPreferredAlternative() {
        var store = new InMemoryLongTermMemory();
        var sink = new GovernanceMemorySink(store, null, 1.0, clock);

        sink.write(entry("prefer", "least-privilege",
                Map.of("user_id", "user-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped credential")));

        var gov = GovernanceFact.parse(
                store.getFacts(GovernanceMemorySink.namespaceKey("user-1"), 10).get(0)).orElseThrow();
        assertTrue(gov.text().contains("request a scoped credential"), gov.text());
        assertEquals(null, gov.expiresAt(), "null ttl -> no expiry");
    }

    @Test
    void ignoresAdmitAndTransform() {
        var store = new InMemoryLongTermMemory();
        var sink = new GovernanceMemorySink(store, null, 1.0, clock);

        sink.write(entry("admit", "", Map.of("user_id", "user-1")));
        sink.write(entry("transform", "rewritten", Map.of("user_id", "user-1")));

        assertTrue(store.getFacts(GovernanceMemorySink.namespaceKey("user-1"), 10).isEmpty(),
                "only deny/prefer are persisted");
    }

    @Test
    void skipsWhenNoUserId() {
        var store = new InMemoryLongTermMemory();
        var sink = new GovernanceMemorySink(store, null, 1.0, clock);

        sink.write(entry("deny", "reason", Map.of("session_id", "sess-1")));

        assertTrue(store.getFacts(GovernanceMemorySink.namespaceKey("sess-1"), 10).isEmpty());
    }

    @Test
    void skipsSelfAuditEntriesToPreventReEntrancy() {
        var store = new InMemoryLongTermMemory();
        var sink = new GovernanceMemorySink(store, null, 1.0, clock);

        var selfAudit = new AuditEntry(Instant.now(), "governance-provenance",
                "code:x", "1.0", "deny", "expired lesson dropped",
                Map.of("user_id", "user-1"), 0.0);
        sink.write(selfAudit);

        assertTrue(store.getFacts(GovernanceMemorySink.namespaceKey("user-1"), 10).isEmpty(),
                "the decorators' own audit entries must never be re-persisted");
    }
}
