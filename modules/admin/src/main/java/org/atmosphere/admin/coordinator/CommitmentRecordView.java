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

import java.util.List;
import java.util.Map;

/**
 * Typed projection of {@link CommitmentRecord} for the
 * {@code GET /api/admin/governance/commitments} endpoint. Owns the
 * admin-surface shape so downstream drift (Jackson property order, field
 * addition, etc.) is caught at compile time instead of at the JSON
 * boundary.
 *
 * <p>Mirrors the {@link CommitmentRecord} fields 1:1 plus a flattened
 * {@link Proof} record and an {@code eventTimestamp} that reports when
 * the journal emitted the entry (which can differ from
 * {@link CommitmentRecord#issuedAt()} when commits are replayed).</p>
 */
public record CommitmentRecordView(
        String id,
        String coordinationId,
        String eventTimestamp,
        String issuer,
        String principal,
        String subject,
        String scope,
        List<String> delegationChain,
        String issuedAt,
        String expiresAt,
        String outcome,
        Map<String, Object> properties,
        Proof proof,
        boolean signed
) {
    /** Build the view from a live journal event. */
    static CommitmentRecordView from(CoordinationEvent.CommitmentRecorded event) {
        var r = event.record();
        return new CommitmentRecordView(
                r.id(),
                r.coordinationId(),
                event.timestamp().toString(),
                r.issuer(),
                r.principal(),
                r.subject(),
                r.scope(),
                r.delegationChain(),
                r.issuedAt().toString(),
                r.expiresAt() == null ? null : r.expiresAt().toString(),
                r.outcome(),
                r.properties(),
                Proof.from(r.proof()),
                r.isSigned());
    }

    /** Flattened {@link CommitmentRecord.Proof} projection. */
    public record Proof(
            String scheme,
            String keyId,
            String signature,
            String createdAt
    ) {
        static Proof from(CommitmentRecord.Proof proof) {
            return new Proof(
                    proof.scheme(),
                    proof.keyId(),
                    proof.signature(),
                    proof.createdAt().toString());
        }
    }
}
