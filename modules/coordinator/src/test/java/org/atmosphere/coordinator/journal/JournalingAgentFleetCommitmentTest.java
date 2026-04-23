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
package org.atmosphere.coordinator.journal;

import org.atmosphere.coordinator.commitment.CommitmentRecordsFlag;
import org.atmosphere.coordinator.commitment.CommitmentSigner;
import org.atmosphere.coordinator.commitment.Ed25519CommitmentSigner;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Phase B1 commitment-record emission path. A signer
 * installed on {@link JournalingAgentFleet} causes every dispatch to
 * emit a signed {@link org.atmosphere.coordinator.commitment.CommitmentRecord}
 * alongside the existing {@code AgentDispatched} event; no signer = no
 * emission (flag-off default).
 */
class JournalingAgentFleetCommitmentTest {

    private InMemoryCoordinationJournal journal;
    private JournalingAgentFleet fleet;
    private AgentTransport transport;

    @BeforeEach
    void setUp() {
        // v4 Phase B1 — commitment records are flag-off by default. These
        // tests exercise emission behavior, so we flip the override on;
        // @AfterEach clears it so other tests stay isolated.
        CommitmentRecordsFlag.override(Boolean.TRUE);
        journal = new InMemoryCoordinationJournal();
        journal.start();
        transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        when(transport.send(any(), any(), any())).thenReturn(
                new AgentResult("billing", "query", "ok", Map.of(),
                        Duration.ofMillis(5), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("billing", new DefaultAgentProxy("billing", "1.0.0", 1, true, transport));

        var delegate = new DefaultAgentFleet(proxies);
        fleet = new JournalingAgentFleet(delegate, journal, "support-coordinator");
    }

    @AfterEach
    void tearDown() {
        fleet.close();
        journal.stop();
        CommitmentRecordsFlag.override(null);
    }

    @Test
    void flagOffGatesEmissionEvenWithSignerInstalled() {
        // Override the BeforeEach enable so this test exercises the v4 gate.
        CommitmentRecordsFlag.override(Boolean.FALSE);
        fleet.signer(Ed25519CommitmentSigner.generate()).principal("user-42");

        fleet.parallel(new AgentCall("billing", "query", Map.of("q", "hi")));

        assertTrue(commitments().isEmpty(),
                "v4 Phase B1: signer installed but flag off -> no emission");
    }

    @Test
    void noSignerNoCommitmentEvent() {
        fleet.parallel(new AgentCall("billing", "query", Map.of("q", "hi")));

        var commits = commitments();
        assertTrue(commits.isEmpty(),
                "no signer installed -> no commitment events");
    }

    @Test
    void signerInstalledEmitsSignedCommitmentPerDispatch() {
        var signer = Ed25519CommitmentSigner.generate();
        fleet.signer(signer).principal("user-42");

        fleet.parallel(
                new AgentCall("billing", "query", Map.of("q", "what is my balance")));

        var commits = commitments();
        assertEquals(1, commits.size());
        var record = commits.get(0).record();
        assertEquals("billing", record.subject());
        assertEquals("query", record.scope());
        assertEquals("user-42", record.principal());
        assertEquals("coordinator:support-coordinator", record.issuer());
        assertEquals("started", record.outcome());
        assertEquals("Ed25519", record.proof().scheme());
        assertFalse(record.proof().signature().isEmpty());
        assertTrue(record.isSigned());
        assertTrue(Ed25519CommitmentSigner.verify(record, signer.publicKey()),
                "emitted record must verify against the signer's public key");
    }

    @Test
    void pipelineEmitsOneCommitmentPerStep() {
        var signer = Ed25519CommitmentSigner.generate();
        fleet.signer(signer);
        fleet.pipeline(
                new AgentCall("billing", "query", Map.of("q", "step 1")),
                new AgentCall("billing", "query", Map.of("q", "step 2")));

        var commits = commitments();
        assertEquals(2, commits.size(),
                "one commitment record per pipeline step: " + commits);
    }

    @Test
    void unsignedSignerEquivalentToNone() {
        fleet.signer(CommitmentSigner.UNSIGNED);
        fleet.parallel(new AgentCall("billing", "query", Map.of()));
        assertTrue(commitments().isEmpty(),
                "UNSIGNED sentinel is a no-op — no emission");
    }

    @Test
    void signerFailureDoesNotBlockDispatch() {
        var throwing = new CommitmentSigner() {
            @Override public String scheme() { return "broken"; }
            @Override public String keyId() { return "key-x"; }
            @Override public org.atmosphere.coordinator.commitment.CommitmentRecord.Proof
                    sign(org.atmosphere.coordinator.commitment.CommitmentRecord record) {
                throw new RuntimeException("HSM unreachable");
            }
        };
        fleet.signer(throwing);

        // Dispatch must succeed even when signing throws.
        var results = fleet.parallel(
                new AgentCall("billing", "query", Map.of("q", "hi")));
        assertNotNull(results.get("billing"));
        assertTrue(results.get("billing").success());
        // The record failure was logged; no commitment entry emitted.
        assertTrue(commitments().isEmpty());
    }

    private List<CoordinationEvent.CommitmentRecorded> commitments() {
        return journal.query(CoordinationQuery.all()).stream()
                .filter(CoordinationEvent.CommitmentRecorded.class::isInstance)
                .map(CoordinationEvent.CommitmentRecorded.class::cast)
                .toList();
    }
}
