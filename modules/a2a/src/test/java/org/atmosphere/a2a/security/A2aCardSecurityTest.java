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
package org.atmosphere.a2a.security;

import org.atmosphere.a2a.types.AgentCapabilities;
import org.atmosphere.a2a.types.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link A2aCardSecurity}: the deployer-declared security-scheme
 * advertisement and the unauthenticated-endpoint warning predicate. An
 * undeclared scheme must leave the card open (unchanged) so the framework never
 * advertises an auth contract it cannot honor (Correctness Invariant #5).
 */
class A2aCardSecurityTest {

    private static AgentCard openCard() {
        return new AgentCard(
                "agent", "desc", List.of(),
                null, "1.0", null,
                new AgentCapabilities(true, false, null, false),
                null, null, null, null, List.of(), null, null);
    }

    @Test
    void bearerSchemeIsAdvertisedWithRequirement() {
        var card = A2aCardSecurity.advertise(openCard(), "bearer", null);
        assertNotNullScheme(card, "bearer");
        var scheme = card.securitySchemes().get("bearer");
        assertNull(scheme.apiKeySecurityScheme());
        assertEquals("bearer", scheme.httpAuthSecurityScheme().scheme());
        assertEquals(1, card.securityRequirements().size());
        assertTrue(card.securityRequirements().get(0).schemes().containsKey("bearer"));
    }

    @Test
    void apiKeySchemeUsesDefaultHeaderWhenUnset() {
        var card = A2aCardSecurity.advertise(openCard(), "apiKey", null);
        assertNotNullScheme(card, "apiKey");
        var scheme = card.securitySchemes().get("apiKey");
        assertEquals(A2aCardSecurity.DEFAULT_API_KEY_HEADER, scheme.apiKeySecurityScheme().name());
        assertEquals("header", scheme.apiKeySecurityScheme().location());
    }

    @Test
    void apiKeySchemeHonorsCustomHeader() {
        var card = A2aCardSecurity.advertise(openCard(), "ApiKey", "X-Tenant-Key");
        assertEquals("X-Tenant-Key", card.securitySchemes().get("apiKey").apiKeySecurityScheme().name());
    }

    @Test
    void blankOrNullOrUnknownSchemeLeavesCardOpen() {
        var open = openCard();
        assertSame(open, A2aCardSecurity.advertise(open, null, null), "null scheme — unchanged");
        assertSame(open, A2aCardSecurity.advertise(open, "  ", null), "blank scheme — unchanged");
        // Unknown scheme returns the same open card (no fabricated contract).
        var unknown = A2aCardSecurity.advertise(open, "kerberos", null);
        assertNull(unknown.securitySchemes(), "unknown scheme advertises nothing");
        assertNull(unknown.securityRequirements());
    }

    @Test
    void shouldWarnOnlyWhenNoRecognisedSchemeAndNotSuppressed() {
        assertTrue(A2aCardSecurity.shouldWarn("", false), "open endpoint warns");
        assertTrue(A2aCardSecurity.shouldWarn(null, false), "null scheme warns");
        assertTrue(A2aCardSecurity.shouldWarn("kerberos", false), "unknown scheme warns");
        assertFalse(A2aCardSecurity.shouldWarn("bearer", false), "declared bearer does not warn");
        assertFalse(A2aCardSecurity.shouldWarn("apiKey", false), "declared apiKey does not warn");
        assertFalse(A2aCardSecurity.shouldWarn("", true), "suppressed never warns");
    }

    private static void assertNotNullScheme(AgentCard card, String name) {
        assertEquals(1, card.securitySchemes().size());
        assertTrue(card.securitySchemes().containsKey(name), "card advertises scheme " + name);
    }
}
