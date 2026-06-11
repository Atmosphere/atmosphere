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

import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.AgentCardSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Signs and verifies A2A {@link AgentCard}s with Ed25519 JWS signatures
 * (A2A v1.0.0). The signed payload is the card with its {@code signatures}
 * field cleared, serialized with {@link JsonCanonicalizer} (RFC 8785 JCS) so
 * any conformant verifier reproduces the same bytes; the JWS is detached
 * (the payload is the card itself, not embedded in the signature).
 *
 * <h2>Two verification modes (fail-closed)</h2>
 *
 * <ul>
 *   <li>{@link #verifyIntegrity(AgentCard)} — checks every signature against
 *       the public key embedded in its own header. This proves the card was
 *       not <em>tampered with</em> after signing, but NOT <em>who</em> signed
 *       it (no trust anchor). Use it to reject corrupted cards.</li>
 *   <li>{@link #verify(AgentCard, EdECPublicKey)} — checks the card against a
 *       pre-shared trusted key. A {@code true} result binds the card to the
 *       holder of that key (identity). Use it when you have pinned the peer's
 *       key out-of-band (e.g. via a JWKS endpoint or registry).</li>
 * </ul>
 *
 * <p>Both modes fail closed: any malformed signature, missing key, or
 * verification error yields {@code false} rather than throwing into the
 * caller (Correctness Invariant #6 — security verification fails closed).</p>
 */
public final class AgentCardSigner {

    private static final Logger logger = LoggerFactory.getLogger(AgentCardSigner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonCanonicalizer CANON = new JsonCanonicalizer(MAPPER);
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final KeyPair keyPair;
    private final String kid;

    public AgentCardSigner(KeyPair keyPair, String kid) {
        this.keyPair = Objects.requireNonNull(keyPair, "keyPair");
        this.kid = Objects.requireNonNull(kid, "kid");
    }

    /**
     * Create a signer with a freshly generated, in-process key pair. The key
     * lives only for the lifetime of the JVM and rotates on restart — it
     * proves card integrity in transit but provides no stable identity. For
     * identity binding, construct with a persistent key and publish the
     * public key (e.g. via JWKS) so verifiers can pin it.
     */
    public static AgentCardSigner ephemeral() {
        return new AgentCardSigner(Ed25519Jwk.generate(), "atmosphere-ephemeral");
    }

    public EdECPublicKey publicKey() {
        return (EdECPublicKey) keyPair.getPublic();
    }

    /**
     * Return a copy of {@code card} with an Ed25519 JWS signature appended.
     * Existing signatures are preserved (A2A allows multiple).
     */
    public AgentCard sign(AgentCard card) {
        var payloadB64 = B64URL.encodeToString(canonicalPayload(card));

        var protectedHeader = new LinkedHashMap<String, Object>();
        protectedHeader.put("alg", "EdDSA");
        var protectedB64 = B64URL.encodeToString(CANON.canonicalize(MAPPER.valueToTree(protectedHeader)));

        var signingInput = (protectedB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII);
        var signatureB64 = B64URL.encodeToString(rawSign(keyPair.getPrivate(), signingInput));

        var header = new LinkedHashMap<String, Object>();
        header.put("kid", kid);
        header.put("jwk", Ed25519Jwk.toJwk(publicKey()));

        var signatures = new ArrayList<AgentCardSignature>(
                card.signatures() != null ? card.signatures() : List.of());
        signatures.add(new AgentCardSignature(protectedB64, signatureB64, header));
        return card.withSignatures(signatures);
    }

    /**
     * Verify every signature on the card against the public key embedded in
     * its own header. Returns {@code true} only if the card carries at least
     * one signature and all signatures verify (tamper detection — see class
     * doc; this is NOT identity verification).
     */
    public static boolean verifyIntegrity(AgentCard card) {
        var signatures = card.signatures();
        if (signatures == null || signatures.isEmpty()) {
            return false;
        }
        var payloadB64 = B64URL.encodeToString(canonicalPayload(card));
        for (var sig : signatures) {
            var embedded = embeddedKey(sig);
            if (embedded == null || !verifyOne(sig, payloadB64, embedded)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Verify the card against a pre-shared trusted key. Returns {@code true}
     * if at least one signature verifies against {@code trusted} (identity
     * binding). Fails closed on any error.
     */
    public static boolean verify(AgentCard card, EdECPublicKey trusted) {
        var signatures = card.signatures();
        if (signatures == null || signatures.isEmpty() || trusted == null) {
            return false;
        }
        var payloadB64 = B64URL.encodeToString(canonicalPayload(card));
        for (var sig : signatures) {
            if (verifyOne(sig, payloadB64, trusted)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] canonicalPayload(AgentCard card) {
        // Sign/verify over the card with signatures cleared.
        return CANON.canonicalize(MAPPER.valueToTree(card.withSignatures(null)));
    }

    @SuppressWarnings("unchecked")
    private static EdECPublicKey embeddedKey(AgentCardSignature sig) {
        try {
            if (sig.header().get("jwk") instanceof Map<?, ?> jwk) {
                return Ed25519Jwk.fromJwk((Map<String, ?>) jwk);
            }
        } catch (RuntimeException e) {
            logger.trace("malformed embedded JWK on AgentCard signature: {}", e.getMessage(), e);
        }
        return null;
    }

    private static boolean verifyOne(AgentCardSignature sig, String payloadB64, EdECPublicKey key) {
        try {
            if (sig.protectedHeader() == null || sig.signature() == null) {
                return false;
            }
            var signingInput = (sig.protectedHeader() + "." + payloadB64)
                    .getBytes(StandardCharsets.US_ASCII);
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signingInput);
            return verifier.verify(B64URL_DEC.decode(sig.signature()));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            // Fail closed — a verification error is a rejected signature, never
            // an exception propagated to the caller (Invariant #6).
            logger.trace("AgentCard signature verification failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private static byte[] rawSign(PrivateKey privateKey, byte[] data) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }
}
