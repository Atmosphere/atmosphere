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

/**
 * Builds {@link CommitmentRecordView} instances from the coordinator's
 * event types. Isolated from {@code CommitmentRecordView} so Spring AOT's
 * response-body walk never encounters coordinator-typed method signatures
 * on samples that do not pull {@code atmosphere-coordinator} transitively.
 */
final class CommitmentRecordViewFactory {

    private CommitmentRecordViewFactory() {
    }

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
                toProof(r.proof()),
                r.isSigned());
    }

    private static CommitmentRecordView.Proof toProof(CommitmentRecord.Proof proof) {
        return new CommitmentRecordView.Proof(
                proof.scheme(),
                proof.keyId(),
                proof.signature(),
                proof.createdAt().toString());
    }
}
