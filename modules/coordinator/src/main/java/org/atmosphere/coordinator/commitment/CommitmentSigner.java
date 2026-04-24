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
package org.atmosphere.coordinator.commitment;

/**
 * Strategy for producing a verifiable {@link CommitmentRecord.Proof} over
 * a {@link CommitmentRecord#canonicalPayload()} byte stream. Default impl
 * is {@link Ed25519CommitmentSigner} which uses the JDK 21 built-in
 * EdDSA provider — no external crypto dependency.
 *
 * <p>Operators wire a custom signer (HSM-backed, Vault-backed, cloud KMS)
 * by implementing this interface and installing it on
 * {@code JournalingAgentFleet.signer(CommitmentSigner)} or publishing via
 * {@link java.util.ServiceLoader}.</p>
 *
 * <p>Implementations MUST be thread-safe.</p>
 */
public interface CommitmentSigner {

    /** No-op signer that produces {@link CommitmentRecord.Proof#UNSIGNED}. */
    CommitmentSigner UNSIGNED = new CommitmentSigner() {
        @Override public String scheme() { return "none"; }
        @Override public String keyId() { return ""; }
        @Override public CommitmentRecord.Proof sign(CommitmentRecord record) {
            return CommitmentRecord.Proof.UNSIGNED;
        }
    };

    /** Signature scheme identifier — e.g. {@code Ed25519}. */
    String scheme();

    /**
     * Public-key identifier to emit on {@link CommitmentRecord.Proof#keyId()}.
     * Verifiers use this to look up the public key for signature
     * verification (DID, fingerprint, key-server reference).
     */
    String keyId();

    /**
     * Sign the record's canonical payload and return a {@link CommitmentRecord.Proof}
     * carrying the signature bytes. The record itself is not mutated; the
     * caller rebuilds it with the returned proof.
     */
    CommitmentRecord.Proof sign(CommitmentRecord record);
}
