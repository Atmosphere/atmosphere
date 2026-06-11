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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.security.interfaces.EdECPublicKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCardSignerTest {

    private static org.atmosphere.a2a.types.AgentCard sampleCard() {
        return new org.atmosphere.a2a.types.AgentCard(
                "billing-agent", "Handles invoices",
                java.util.List.of(new org.atmosphere.a2a.types.AgentInterface(
                        "/a2a", org.atmosphere.a2a.types.AgentInterface.JSONRPC, "1.0")),
                null, "1.0", null,
                new org.atmosphere.a2a.types.AgentCapabilities(true, false, null, true),
                null, null, null, null,
                java.util.List.of(), null, null);
    }

    @Test
    void signThenVerifyIntegrityRoundTrips() {
        var signer = AgentCardSigner.ephemeral();
        var signed = signer.sign(sampleCard());

        assertEquals(1, signed.signatures().size(), "signature appended to the card");
        assertTrue(AgentCardSigner.verifyIntegrity(signed),
                "a freshly signed card verifies against its embedded key");
    }

    @Test
    void tamperedCardFailsVerification() {
        var signer = AgentCardSigner.ephemeral();
        var signed = signer.sign(sampleCard());

        // Mutate a signed field while keeping the original signature.
        var tampered = new org.atmosphere.a2a.types.AgentCard(
                "billing-agent", "Handles invoices AND drains the account",
                signed.supportedInterfaces(), signed.provider(), signed.version(),
                signed.documentationUrl(), signed.capabilities(), signed.securitySchemes(),
                signed.securityRequirements(), signed.defaultInputModes(),
                signed.defaultOutputModes(), signed.skills(), signed.signatures(),
                signed.iconUrl());

        assertFalse(AgentCardSigner.verifyIntegrity(tampered),
                "mutating a signed field must break the signature");
    }

    @Test
    void verifyAgainstTrustedKeySucceedsAndWrongKeyFails() {
        var signer = AgentCardSigner.ephemeral();
        var other = AgentCardSigner.ephemeral();
        var signed = signer.sign(sampleCard());

        assertTrue(AgentCardSigner.verify(signed, signer.publicKey()),
                "verifies against the actual signing key (identity)");
        assertFalse(AgentCardSigner.verify(signed, other.publicKey()),
                "does not verify against an unrelated key");
    }

    @Test
    void unsignedCardFailsIntegrity() {
        assertFalse(AgentCardSigner.verifyIntegrity(sampleCard()),
                "a card with no signatures is not 'verified' — fail closed");
    }

    @Test
    void multipleSignaturesAllVerify() {
        var a = AgentCardSigner.ephemeral();
        var b = AgentCardSigner.ephemeral();
        var doubleSigned = b.sign(a.sign(sampleCard()));

        assertEquals(2, doubleSigned.signatures().size());
        assertTrue(AgentCardSigner.verifyIntegrity(doubleSigned),
                "every embedded signature must verify for integrity to hold");
        assertTrue(AgentCardSigner.verify(doubleSigned, a.publicKey()));
        assertTrue(AgentCardSigner.verify(doubleSigned, b.publicKey()));
    }

    @Test
    void jwkRoundTripReproducesPublicKey() {
        var keyPair = Ed25519Jwk.generate();
        var pub = (EdECPublicKey) keyPair.getPublic();

        var jwk = Ed25519Jwk.toJwk(pub);
        assertEquals("OKP", jwk.get("kty"));
        assertEquals("Ed25519", jwk.get("crv"));

        var reconstructed = Ed25519Jwk.fromJwk(jwk);
        assertArrayEquals(Ed25519Jwk.rawPublicKey(pub), Ed25519Jwk.rawPublicKey(reconstructed),
                "JWK encode/decode preserves the raw Ed25519 public key bytes");
    }

    @Test
    void signedCardSurvivesWireSerialization() {
        // The integration risk: does the JWS survive being serialized to JSON
        // (the .well-known/agent.json the A2A handler serves) and parsed back?
        // Catches any mismatch in the "protected" field name or the embedded
        // jwk map shape.
        var mapper = new ObjectMapper();
        var signed = AgentCardSigner.ephemeral().sign(sampleCard());

        var json = mapper.writeValueAsString(signed);
        var reparsed = mapper.readValue(json, org.atmosphere.a2a.types.AgentCard.class);

        assertTrue(AgentCardSigner.verifyIntegrity(reparsed),
                "signature must still verify after a full JSON serialize/parse round-trip");
        assertTrue(json.contains("\"protected\""),
                "JWS protected header serializes under the spec field name");
    }

    @Test
    void canonicalizationIsPropertyOrderInsensitive() {
        var mapper = new ObjectMapper();
        var canon = new JsonCanonicalizer(mapper);
        var a = canon.canonicalize(mapper.readTree("{\"b\":1,\"a\":[true,\"x\"],\"c\":null}"));
        var b = canon.canonicalize(mapper.readTree("{\"a\":[true,\"x\"],\"c\":null,\"b\":1}"));
        assertArrayEquals(a, b, "canonical bytes are independent of property insertion order");
        assertEquals("{\"a\":[true,\"x\"],\"b\":1,\"c\":null}",
                new String(a, java.nio.charset.StandardCharsets.UTF_8));
    }
}
