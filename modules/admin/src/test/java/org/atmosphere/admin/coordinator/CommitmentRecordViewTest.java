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
package org.atmosphere.admin.coordinator;

import org.atmosphere.coordinator.commitment.CommitmentRecord;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape pin on {@link CommitmentRecordView}. External consumers of the
 * {@code /api/admin/governance/commitments} endpoint rely on the field
 * set; adding or renaming a field silently breaks verifiers that parse
 * the JSON. The typed record closes that loophole — this test only
 * protects against accidental removal by verifying a round-trip from a
 * live record into the view preserves every field.
 */
class CommitmentRecordViewTest {

    @Test
    void fromPreservesEveryFieldOfTheRecord() {
        var proof = new CommitmentRecord.Proof(
                "Ed25519", "ed25519:abc123", "sig-bytes",
                Instant.parse("2026-04-22T19:00:00Z"));
        var record = new CommitmentRecord(
                "rec-1", "coord-1",
                "coordinator:support",
                "user-42",
                "billing-agent",
                "query",
                List.of("user-42", "coordinator:support"),
                Instant.parse("2026-04-22T18:55:00Z"),
                Instant.parse("2026-04-22T19:15:00Z"),
                "completed",
                Map.of("model", "gpt-4o", "cost", 0.002),
                proof);
        var event = new CoordinationEvent.CommitmentRecorded(
                "coord-1", record, Instant.parse("2026-04-22T19:00:01Z"));

        var view = CommitmentRecordViewFactory.from(event);

        assertEquals("rec-1", view.id());
        assertEquals("coord-1", view.coordinationId());
        assertEquals("2026-04-22T19:00:01Z", view.eventTimestamp());
        assertEquals("coordinator:support", view.issuer());
        assertEquals("user-42", view.principal());
        assertEquals("billing-agent", view.subject());
        assertEquals("query", view.scope());
        assertEquals(List.of("user-42", "coordinator:support"), view.delegationChain());
        assertEquals("2026-04-22T18:55:00Z", view.issuedAt());
        assertEquals("2026-04-22T19:15:00Z", view.expiresAt());
        assertEquals("completed", view.outcome());
        assertEquals(Map.of("model", "gpt-4o", "cost", 0.002), view.properties());
        assertTrue(view.signed());

        assertEquals("Ed25519", view.proof().scheme());
        assertEquals("ed25519:abc123", view.proof().keyId());
        assertEquals("sig-bytes", view.proof().signature());
        assertEquals("2026-04-22T19:00:00Z", view.proof().createdAt());
    }

    @Test
    void nullExpiresAtRoundTripsAsNullString() {
        var record = new CommitmentRecord(
                "rec-1", "coord-1",
                "coordinator:support", null, "billing-agent", null,
                List.of(), Instant.parse("2026-04-22T18:55:00Z"),
                null, "started",
                Map.of(),
                null);
        var event = new CoordinationEvent.CommitmentRecorded(
                "coord-1", record, Instant.parse("2026-04-22T18:55:00Z"));

        var view = CommitmentRecordViewFactory.from(event);
        assertNull(view.expiresAt(),
                "null issuesAt field maps to null JSON, not a placeholder string");
        assertEquals(false, view.signed(),
                "unsigned record reports signed=false");
        assertEquals("none", view.proof().scheme(),
                "UNSIGNED proof surfaces as scheme=none");
    }
}
