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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural invariants on {@link CommitmentRecord}: canonical payload is
 * deterministic across re-constructions with the same fields, signing
 * round-trip via Ed25519 produces verifiable proof, and rejected inputs
 * throw on construction.
 */
class CommitmentRecordTest {

    @Test
    void canonicalPayloadDeterministicForSameFields() {
        var props = new LinkedHashMap<String, Object>();
        props.put("model", "gpt-4o-mini");
        props.put("cost", 0.002);

        var r1 = new CommitmentRecord(
                "id-1", "coord-1",
                "coordinator:support",
                "user-42",
                "billing-agent",
                "query",
                List.of("user-42", "coordinator:support"),
                Instant.parse("2026-04-22T19:00:00Z"),
                null,
                "started",
                props,
                null);
        var r2 = new CommitmentRecord(
                "id-1", "coord-1",
                "coordinator:support",
                "user-42",
                "billing-agent",
                "query",
                List.of("user-42", "coordinator:support"),
                Instant.parse("2026-04-22T19:00:00Z"),
                null,
                "started",
                props,
                null);
        assertEquals(r1.canonicalPayload(), r2.canonicalPayload(),
                "canonical payload must be deterministic: " + r1.canonicalPayload());
    }

    @Test
    void canonicalPayloadContainsKeyFields() {
        var record = new CommitmentRecord(
                "id-1", "coord-1",
                "coordinator:support",
                "user-42",
                "billing-agent",
                "query",
                List.of("user-42", "coordinator:support"),
                Instant.parse("2026-04-22T19:00:00Z"),
                null,
                "started",
                Map.of("model", "gpt-4o"),
                null);
        var payload = record.canonicalPayload();
        assertTrue(payload.contains("id=id-1"));
        assertTrue(payload.contains("coordinationId=coord-1"));
        assertTrue(payload.contains("issuer=coordinator:support"));
        assertTrue(payload.contains("subject=billing-agent"));
        assertTrue(payload.contains("outcome=started"));
        assertTrue(payload.contains("delegationChain=user-42,coordinator:support"));
        assertTrue(payload.contains("prop:model=gpt-4o"));
    }

    @Test
    void ed25519SignatureRoundTripVerifies() {
        var signer = Ed25519CommitmentSigner.generate();
        var record = new CommitmentRecord(
                "id-1", "coord-1",
                "coordinator:support",
                "user-42",
                "billing-agent",
                "query",
                List.of(),
                Instant.parse("2026-04-22T19:00:00Z"),
                null,
                "started",
                Map.of(),
                null);
        var proof = signer.sign(record);
        assertEquals("Ed25519", proof.scheme());
        assertFalse(proof.signature().isEmpty());
        var signed = new CommitmentRecord(
                record.id(), record.coordinationId(), record.issuer(),
                record.principal(), record.subject(), record.scope(),
                record.delegationChain(), record.issuedAt(), record.expiresAt(),
                record.outcome(), record.properties(), proof);
        assertTrue(Ed25519CommitmentSigner.verify(signed, signer.publicKey()),
                "round-trip signature must verify against the signer's public key");
        assertTrue(signed.isSigned());
    }

    @Test
    void tamperedPayloadFailsVerification() {
        var signer = Ed25519CommitmentSigner.generate();
        var record = new CommitmentRecord(
                "id-1", "coord-1", "coordinator:support",
                "user-42", "billing-agent", "query",
                List.of(), Instant.parse("2026-04-22T19:00:00Z"),
                null, "started", Map.of(), null);
        var proof = signer.sign(record);

        // Swap the outcome but keep the same proof — verification must fail.
        var tampered = new CommitmentRecord(
                record.id(), record.coordinationId(), record.issuer(),
                record.principal(), record.subject(), record.scope(),
                record.delegationChain(), record.issuedAt(), record.expiresAt(),
                "completed",  // <-- changed
                record.properties(), proof);
        assertFalse(Ed25519CommitmentSigner.verify(tampered, signer.publicKey()),
                "verification must fail when the canonical payload changed "
                        + "but the proof stayed the same — that's the whole point");
    }

    @Test
    void wrongPublicKeyFailsVerification() {
        var signerA = Ed25519CommitmentSigner.generate();
        var signerB = Ed25519CommitmentSigner.generate();
        var record = new CommitmentRecord(
                "id-1", "coord-1", "coordinator:support",
                "user-42", "billing-agent", "query",
                List.of(), Instant.parse("2026-04-22T19:00:00Z"),
                null, "started", Map.of(), null);
        var proof = signerA.sign(record);
        var signed = new CommitmentRecord(
                record.id(), record.coordinationId(), record.issuer(),
                record.principal(), record.subject(), record.scope(),
                record.delegationChain(), record.issuedAt(), record.expiresAt(),
                record.outcome(), record.properties(), proof);
        assertFalse(Ed25519CommitmentSigner.verify(signed, signerB.publicKey()),
                "verification under the wrong public key must fail");
    }

    @Test
    void unsignedRecordIsNotSigned() {
        var record = new CommitmentRecord(
                "id", "coord", "issuer", null, "subject", null,
                List.of(), Instant.now(), null, "started", Map.of(), null);
        assertFalse(record.isSigned());
        assertEquals(CommitmentRecord.Proof.UNSIGNED, record.proof());
    }

    @Test
    void missingRequiredFieldsThrow() {
        assertThrows(NullPointerException.class,
                () -> new CommitmentRecord(null, "c", "i", null, "s", null,
                        List.of(), Instant.now(), null, "o", Map.of(), null));
        assertThrows(NullPointerException.class,
                () -> new CommitmentRecord("i", "c", null, null, "s", null,
                        List.of(), Instant.now(), null, "o", Map.of(), null));
        assertThrows(NullPointerException.class,
                () -> new CommitmentRecord("i", "c", "i", null, null, null,
                        List.of(), Instant.now(), null, "o", Map.of(), null));
    }

    @Test
    void unsignedSignerProducesUnsignedProof() {
        var record = new CommitmentRecord(
                "id", "coord", "issuer", null, "subject", null,
                List.of(), Instant.now(), null, "started", Map.of(), null);
        var proof = CommitmentSigner.UNSIGNED.sign(record);
        assertEquals(CommitmentRecord.Proof.UNSIGNED, proof);
    }

    @Test
    void ed25519SignerBlankKeyIdRejected() {
        var pair = Ed25519CommitmentSigner.generate();
        assertThrows(IllegalArgumentException.class,
                () -> new Ed25519CommitmentSigner(pair.publicKey() == null ? null
                        : java.security.KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair(), ""));
    }
}
