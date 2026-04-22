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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentStateIntegrity} seal/verify round-trip. Covers the three
 * failure modes the memory-poisoning row cares about:
 *
 * <ul>
 *   <li>content tampering — bytes edited, seal stays the same;</li>
 *   <li>key tampering — seal moved from one memory slot to another;</li>
 *   <li>public-key mismatch — seal verified against the wrong key.</li>
 * </ul>
 */
class AgentStateIntegrityTest {

    @Test
    void sealAndVerifyRoundTrip() {
        var integrity = AgentStateIntegrity.generate();
        var seal = integrity.seal("facts:user-42:agent-1",
                "User prefers bun over npm for RN projects.");
        assertTrue(seal.isPresent());
        assertEquals("Ed25519", seal.scheme());
        assertTrue(integrity.verify("facts:user-42:agent-1",
                        "User prefers bun over npm for RN projects.", seal),
                "round-trip must verify");
    }

    @Test
    void contentTamperingFailsVerification() {
        var integrity = AgentStateIntegrity.generate();
        var key = "facts:user-42:agent-1";
        var original = "User prefers bun over npm.";
        var seal = integrity.seal(key, original);

        assertFalse(integrity.verify(key, "User prefers npm over bun.", seal),
                "verify must reject when content changed but seal stayed the same — "
                        + "that's the A03 memory-poisoning guarantee");
    }

    @Test
    void keyReplayFailsVerification() {
        var integrity = AgentStateIntegrity.generate();
        var seal = integrity.seal("facts:user-42:agent-1",
                "User prefers bun over npm.");
        // Attacker moves the seal to a different memory slot but keeps
        // the same content — must still fail.
        assertFalse(integrity.verify("facts:user-99:agent-1",
                "User prefers bun over npm.", seal),
                "cross-slot replay must fail — the domain-separated payload "
                        + "binds content to its key");
    }

    @Test
    void wrongPublicKeyFailsVerification() {
        var integrityA = AgentStateIntegrity.generate();
        var integrityB = AgentStateIntegrity.generate();
        var seal = integrityA.seal("k", "content");
        assertFalse(AgentStateIntegrity.verify("k", "content", seal, integrityB.publicKey()),
                "seal signed by A must not verify under B's public key");
    }

    @Test
    void emptySealNeverVerifies() {
        var integrity = AgentStateIntegrity.generate();
        assertFalse(integrity.verify("k", "c", AgentStateIntegrity.Seal.EMPTY),
                "EMPTY sentinel fails verification by construction");
        assertFalse(AgentStateIntegrity.Seal.EMPTY.isPresent());
    }

    @Test
    void nullKeyOrContentStillSigns() {
        // Defensive — treat nulls as empty strings rather than NPE. Operators
        // using the utility on cleared memory slots benefit from this.
        var integrity = AgentStateIntegrity.generate();
        var seal1 = integrity.seal("k", null);
        var seal2 = integrity.seal("k", null);
        // Same nulls produce verifiable seals; different signatures per
        // call are fine because Ed25519 is deterministic per payload only,
        // but both must verify.
        assertTrue(integrity.verify("k", null, seal1));
        assertTrue(integrity.verify("k", null, seal2));
    }

    @Test
    void blankKeyIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentStateIntegrity(
                        java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
                        " "));
    }

    @Test
    void keyIdExposed() {
        var integrity = AgentStateIntegrity.generate();
        assertTrue(integrity.keyId().startsWith("ed25519:"),
                "fingerprint-derived keyId carries ed25519: prefix");
    }
}
