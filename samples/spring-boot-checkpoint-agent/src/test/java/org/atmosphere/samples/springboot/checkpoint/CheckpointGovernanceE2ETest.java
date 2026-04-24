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
package org.atmosphere.samples.springboot.checkpoint;

import org.atmosphere.coordinator.commitment.CommitmentRecordsFlag;
import org.atmosphere.coordinator.commitment.CommitmentSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commitment-record wiring in the checkpoint-agent sample: Ed25519
 * signer installed, commitment-records flag enabled at boot. The
 * pause/resume flow itself is exercised in {@code DispatchCoordinator}
 * at runtime — this test locks down the wiring so a future refactor
 * can't silently remove the signer and leave the sample claiming a
 * signed audit trail it no longer produces.
 *
 * <p>Atmosphere-unique differentiator: durable session + signed audit
 * trail across pause/resume. MS Agent Framework drops state on pause;
 * LangChain has no checkpoint primitive.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class CheckpointGovernanceE2ETest {

    @Autowired CommitmentSigner signer;

    @Test
    void goal3_ed25519SignerInstalled() {
        assertNotNull(signer);
        assertEquals("Ed25519", signer.scheme());
        assertNotNull(signer.keyId());
    }

    @Test
    void goal3_commitmentRecordsFlagEnabledAtBoot() {
        assertTrue(CommitmentRecordsFlag.isEnabled(),
                "CommitmentConfig must flip the commitment-records flag on at boot");
    }

    @Test
    void goal3_signerCanSignAndVerifyARecord() {
        // A real-shape record — we don't need to go through the fleet to
        // prove signing round-trips. Shape mirrors what DispatchCoordinator
        // emits via JournalingAgentFleet on each analyzer/approver dispatch.
        var record = new org.atmosphere.coordinator.commitment.CommitmentRecord(
                java.util.UUID.randomUUID().toString(),
                "coord-42",
                "coordinator:dispatch",
                "user:session-x",
                "analyzer",
                "analyze",
                java.util.List.of(),
                java.time.Instant.now(),
                null,
                "started",
                java.util.Map.of("request", "please analyze these logs"),
                null);
        var proof = signer.sign(record);
        assertNotNull(proof);
        assertEquals("Ed25519", proof.scheme());

        var signed = new org.atmosphere.coordinator.commitment.CommitmentRecord(
                record.id(), record.coordinationId(), record.issuer(),
                record.principal(), record.subject(), record.scope(),
                record.delegationChain(), record.issuedAt(), record.expiresAt(),
                record.outcome(), record.properties(), proof);
        var publicKey = ((org.atmosphere.coordinator.commitment.Ed25519CommitmentSigner) signer)
                .publicKey();
        assertTrue(org.atmosphere.coordinator.commitment.Ed25519CommitmentSigner.verify(
                signed, publicKey),
                "signed record must verify against the signer's public key");
    }
}
