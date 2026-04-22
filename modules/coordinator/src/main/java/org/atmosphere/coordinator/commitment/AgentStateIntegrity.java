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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Base64;

/**
 * Ed25519-backed integrity seal for {@code AgentState} memory snapshots.
 * Operators who need tamper-evident memory install one instance per
 * deployment, then wrap their {@code AgentState} with a
 * {@code SigningAgentState} decorator that consults this helper on every
 * read/write.
 *
 * <h2>Design</h2>
 * Content-addressed seal: SHA-256 the content, Ed25519-sign the hash.
 * Verifier re-hashes and re-verifies. Two pieces:
 * <ul>
 *   <li>{@link #seal(String, String)} — called on write; returns a
 *       {@link Seal} carrying the signature + key id + timestamp.</li>
 *   <li>{@link #verify(String, String, Seal)} — called on read; returns
 *       {@code true} only when the signature verifies for the same key
 *       under the current public key.</li>
 * </ul>
 *
 * <p>The {@code key} argument identifies the memory slot (e.g.
 * {@code conversation:agent-1:session-42}, {@code facts:user-7:agent-1})
 * so a seal for one slot cannot be replayed into another.</p>
 *
 * <p>Sibling of {@link Ed25519CommitmentSigner} — both use the same
 * JDK 21 built-in EdDSA provider; same key-management story. A
 * deployment that signs both dispatch records and memory snapshots can
 * share a single {@link KeyPair} across both and publish one public
 * key for all verifiers.</p>
 *
 * <p>Closes v5 Tier 3.2 / OWASP Agentic A03 Memory Poisoning row —
 * the companion evidence to {@link CommitmentRecord} which covers
 * dispatch records on the coordinator.</p>
 */
public final class AgentStateIntegrity {

    private static final Logger logger = LoggerFactory.getLogger(AgentStateIntegrity.class);
    private static final String SCHEME = "Ed25519";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    public AgentStateIntegrity(KeyPair keyPair, String keyId) {
        this(keyPair.getPrivate(), keyPair.getPublic(), keyId);
    }

    public AgentStateIntegrity(PrivateKey privateKey, PublicKey publicKey, String keyId) {
        if (privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("privateKey and publicKey must not be null");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.keyId = keyId;
    }

    /** Mint a fresh Ed25519 keypair + derive a fingerprint keyId. */
    public static AgentStateIntegrity generate() {
        try {
            var gen = KeyPairGenerator.getInstance(SCHEME);
            var pair = gen.generateKeyPair();
            return new AgentStateIntegrity(pair, Ed25519CommitmentSigner.fingerprint(pair.getPublic()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK does not ship Ed25519 — requires JDK 15+", e);
        }
    }

    /**
     * Produce a seal over {@code key || "\0" || content}. The null-byte
     * domain separator prevents a seal computed for
     * {@code "foo" + "bar"} being accepted as a seal for {@code "foob" + "ar"}.
     */
    public Seal seal(String key, String content) {
        var payload = payload(key, content);
        try {
            var signature = Signature.getInstance(SCHEME);
            signature.initSign(privateKey);
            signature.update(payload);
            var sigBytes = signature.sign();
            var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
            return new Seal(SCHEME, keyId, encoded, Instant.now());
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            logger.error("AgentStateIntegrity seal failed for key '{}': {}", key, e.toString());
            return Seal.EMPTY;
        }
    }

    /** Verify a seal against the public key held by this instance. */
    public boolean verify(String key, String content, Seal seal) {
        return verify(key, content, seal, publicKey);
    }

    /**
     * Verify a seal against an arbitrary public key — useful when the
     * verifier side does not hold the private key.
     */
    public static boolean verify(String key, String content, Seal seal, PublicKey publicKey) {
        if (seal == null || seal == Seal.EMPTY || !SCHEME.equals(seal.scheme())) {
            return false;
        }
        try {
            var sigBytes = Base64.getUrlDecoder().decode(seal.signature());
            var signature = Signature.getInstance(SCHEME);
            signature.initVerify(publicKey);
            signature.update(payload(key, content));
            return signature.verify(sigBytes);
        } catch (IllegalArgumentException | NoSuchAlgorithmException
                 | InvalidKeyException | SignatureException e) {
            logger.debug("AgentStateIntegrity verify failed for key '{}': {}", key, e.toString());
            return false;
        }
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public String keyId() {
        return keyId;
    }

    private static byte[] payload(String key, String content) {
        var keyBytes = (key == null ? "" : key).getBytes(StandardCharsets.UTF_8);
        var contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        var buf = new byte[keyBytes.length + 1 + contentBytes.length];
        System.arraycopy(keyBytes, 0, buf, 0, keyBytes.length);
        // domain separator — a null byte is invalid in UTF-8 string keys,
        // so a collision between (key, content) pairs is impossible as
        // long as the key itself came from real text.
        buf[keyBytes.length] = 0;
        System.arraycopy(contentBytes, 0, buf, keyBytes.length + 1, contentBytes.length);
        return buf;
    }

    /**
     * Integrity seal — scheme, key id, base64url signature, and signing
     * timestamp. {@link #EMPTY} sentinel carries no signature and fails
     * {@link #verify} by construction; useful as a null-safe default.
     */
    public record Seal(String scheme, String keyId, String signature, Instant createdAt) {

        public static final Seal EMPTY = new Seal("", "", "", Instant.EPOCH);

        public Seal {
            scheme = scheme == null ? "" : scheme;
            keyId = keyId == null ? "" : keyId;
            signature = signature == null ? "" : signature;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        }

        public boolean isPresent() {
            return !this.equals(EMPTY) && !signature.isEmpty();
        }
    }
}
