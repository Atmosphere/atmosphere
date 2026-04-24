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

import java.util.List;
import java.util.Map;

/**
 * Typed projection of the coordinator's commitment record for the
 * {@code GET /api/admin/governance/commitments} endpoint. Owns the
 * admin-surface shape so downstream drift (Jackson property order, field
 * addition, etc.) is caught at compile time instead of at the JSON
 * boundary.
 *
 * <p>Mirrors the coordinator's {@code CommitmentRecord} fields 1:1 plus a
 * flattened {@link Proof} record and an {@code eventTimestamp} that reports
 * when the journal emitted the entry (which can differ from {@code issuedAt}
 * when commits are replayed).</p>
 *
 * <p>Construction from the coordinator's event types lives in
 * {@link CommitmentRecordViewFactory}; keeping coordinator-typed parameters
 * out of this record's method signatures lets Spring AOT introspect the
 * response body on samples that don't pull {@code atmosphere-coordinator}
 * transitively (the coordinator dep is {@code optional=true}).</p>
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
    /** Flattened projection of the coordinator's signing proof. */
    public record Proof(
            String scheme,
            String keyId,
            String signature,
            String createdAt
    ) {
    }
}
