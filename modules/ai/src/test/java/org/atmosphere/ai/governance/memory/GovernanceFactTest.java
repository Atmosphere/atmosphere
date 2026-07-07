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
package org.atmosphere.ai.governance.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceFactTest {

    @Test
    void encodeParseRoundTrip() {
        var exp = Instant.ofEpochSecond(1_900_000_000L);
        var encoded = GovernanceFact.encode("least-privilege", 0.9, exp, "Prefer: scoped credential");
        var parsed = GovernanceFact.parse(encoded).orElseThrow();
        assertEquals("least-privilege", parsed.policy());
        assertEquals(0.9, parsed.confidence(), 0.001);
        assertEquals(exp, parsed.expiresAt());
        assertEquals("Prefer: scoped credential", parsed.text());
    }

    @Test
    void noExpiryEncodesAsDash() {
        var encoded = GovernanceFact.encode("p", 1.0, null, "guidance");
        assertTrue(encoded.contains("exp=-"), encoded);
        assertEquals(null, GovernanceFact.parse(encoded).orElseThrow().expiresAt());
    }

    @Test
    void isExpiredHonorsInstant() {
        var exp = Instant.ofEpochSecond(1000);
        var fact = new GovernanceFact("p", 1.0, exp, "t");
        assertFalse(fact.isExpired(Instant.ofEpochSecond(999)));
        assertTrue(fact.isExpired(Instant.ofEpochSecond(1000)), "expiry is inclusive");
        assertTrue(fact.isExpired(Instant.ofEpochSecond(1001)));
        assertFalse(new GovernanceFact("p", 1.0, null, "t").isExpired(Instant.MAX),
                "null expiry never expires");
    }

    @Test
    void nonGovernanceStringParsesEmpty() {
        assertTrue(GovernanceFact.parse("Has a dog named Max").isEmpty());
        assertTrue(GovernanceFact.parse(null).isEmpty());
        assertFalse(GovernanceFact.isGovernanceFact("ordinary user fact"));
    }

    @Test
    void malformedMarkerParsesEmptyRatherThanThrowing() {
        // A string that opens the marker but is garbled is treated conservatively as a
        // non-governance fact (passed through), never dropped and never an exception.
        assertTrue(GovernanceFact.parse("[atmo-gov v=1 policy=p conf=notanumber exp=-] x").isEmpty());
        assertTrue(GovernanceFact.parse("[atmo-gov no-close-bracket").isEmpty());
        assertTrue(GovernanceFact.parse("[atmo-gov v=1 policy=p conf=0.5 exp=-] ").isEmpty(),
                "blank text is not a valid governance fact");
    }

    @Test
    void policyNameCannotBreakTheEnvelope() {
        // A policy name carrying spaces or ']' must not corrupt the marker.
        var encoded = GovernanceFact.encode("evil] policy name", 1.0, null, "guidance");
        var parsed = GovernanceFact.parse(encoded).orElseThrow();
        assertEquals("guidance", parsed.text());
        assertFalse(parsed.policy().contains("]"), "reserved ']' stripped from policy: " + parsed.policy());
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new GovernanceFact("  ", 1.0, null, "t"));
        assertThrows(IllegalArgumentException.class, () -> new GovernanceFact("p", 1.5, null, "t"));
        assertThrows(IllegalArgumentException.class, () -> new GovernanceFact("p", -0.1, null, "t"));
        assertThrows(IllegalArgumentException.class, () -> new GovernanceFact("p", 1.0, null, "  "));
    }
}
