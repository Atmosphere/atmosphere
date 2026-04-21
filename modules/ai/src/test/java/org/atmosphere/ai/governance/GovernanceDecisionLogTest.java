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

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceDecisionLogTest {

    @AfterEach
    void cleanup() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void defaultIsNoopAndDropsEntries() {
        // Without install(), the default is NOOP — record() is a no-op and
        // recent() always returns empty.
        GovernanceDecisionLog.installed().record(
                GovernanceDecisionLog.entryWithSnapshot(null, Map.of(), "admit", "", 1.0));
        assertTrue(GovernanceDecisionLog.installed().recent(10).isEmpty());
    }

    @Test
    void ringBufferEvictsOldest() {
        var log = GovernanceDecisionLog.install(3);
        record(log, "p1");
        record(log, "p2");
        record(log, "p3");
        record(log, "p4");
        record(log, "p5");

        var recent = log.recent(10);
        // Newest first, oldest evicted: p5, p4, p3
        assertEquals(3, recent.size());
        assertEquals("p5", recent.get(0).policyName());
        assertEquals("p4", recent.get(1).policyName());
        assertEquals("p3", recent.get(2).policyName());
    }

    @Test
    void recentRespectsLimit() {
        var log = GovernanceDecisionLog.install(10);
        for (int i = 0; i < 5; i++) {
            record(log, "p" + i);
        }
        assertEquals(2, log.recent(2).size());
        assertEquals(5, log.recent(100).size());
        assertEquals(0, log.recent(0).size());
        assertEquals(0, log.recent(-1).size());
    }

    @Test
    void contextSnapshotIsRedactionSafe() {
        var req = new AiRequest(
                "a".repeat(500), // will be truncated
                "sys", "gpt-4o",
                "user-1", "sess-1", "agent-1", "conv-1",
                Map.of("business.tenant.id", "tenant-a",
                        "custom.complex", new Object[] { 1, 2, 3 },
                        "numeric", 42),
                List.of());

        var snapshot = GovernanceDecisionLog.snapshotContext(PolicyContext.preAdmission(req));
        assertEquals("pre_admission", snapshot.get("phase"));
        assertEquals("gpt-4o", snapshot.get("model"));
        assertEquals("user-1", snapshot.get("user_id"));
        assertEquals("tenant-a", snapshot.get("business.tenant.id"));
        assertEquals(42, snapshot.get("numeric"));

        var message = (String) snapshot.get("message");
        assertTrue(message.length() <= GovernanceDecisionLog.MESSAGE_SNAPSHOT_MAX_CHARS + 1,
                "message must be truncated: " + message.length());
        assertTrue(message.endsWith("…"),
                "truncation marker must be present: " + message);
        // Complex metadata value is coerced to a string, not serialized as-is.
        var complex = snapshot.get("custom.complex");
        assertTrue(complex instanceof String,
                "non-primitive metadata coerced to string: " + complex);
    }

    @Test
    void installZeroCapacityIsValidButNoop() {
        var log = GovernanceDecisionLog.install(0);
        record(log, "p");
        assertTrue(log.recent(10).isEmpty());
        assertFalse(log.capacity() > 0);
    }

    @Test
    void rejectsNegativeCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> GovernanceDecisionLog.install(-1));
    }

    private static void record(GovernanceDecisionLog log, String name) {
        log.record(GovernanceDecisionLog.entryWithSnapshot(
                new FakePolicy(name), Map.of(), "admit", "", 0.5));
    }

    private record FakePolicy(String name) implements GovernancePolicy {
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext context) { return PolicyDecision.admit(); }
    }
}
