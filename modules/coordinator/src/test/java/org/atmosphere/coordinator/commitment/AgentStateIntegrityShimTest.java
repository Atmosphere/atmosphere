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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The released {@code org.atmosphere.coordinator.commitment.AgentStateIntegrity}
 * coordinates forward to the relocated
 * {@link org.atmosphere.ai.state.seal.AgentStateIntegrity}; this pins that the
 * forwarder still round-trips and that its seals interoperate with the
 * relocated primitive (same payload, same scheme — a seal minted through the
 * old API verifies through the new one).
 */
// Suppression justified: this test exists to exercise the deprecated
// forwarding shim, which cannot be done without referencing deprecated API.
@SuppressWarnings("deprecation")
class AgentStateIntegrityShimTest {

    @Test
    void shimSealRoundTrips() {
        var shim = AgentStateIntegrity.generate();
        var seal = shim.seal("facts:user-1:agent-1", "content");
        assertTrue(seal.isPresent());
        assertTrue(shim.verify("facts:user-1:agent-1", "content", seal));
        assertFalse(shim.verify("facts:user-1:agent-1", "tampered", seal));
    }

    @Test
    void shimSealVerifiesThroughRelocatedPrimitive() {
        var shim = AgentStateIntegrity.generate();
        var seal = shim.seal("slot", "content");
        var relocated = new org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal(
                seal.scheme(), seal.keyId(), seal.signature(), seal.createdAt());
        assertTrue(org.atmosphere.ai.state.seal.AgentStateIntegrity.verify(
                        "slot", "content", relocated, shim.publicKey()),
                "a seal minted through the deprecated coordinates must verify "
                        + "through the relocated primitive — same payload, same scheme");
    }

    @Test
    void shimEmptySentinelStillFailsByConstruction() {
        var shim = AgentStateIntegrity.generate();
        assertFalse(shim.verify("k", "c", AgentStateIntegrity.Seal.EMPTY));
        assertFalse(AgentStateIntegrity.Seal.EMPTY.isPresent());
    }
}
