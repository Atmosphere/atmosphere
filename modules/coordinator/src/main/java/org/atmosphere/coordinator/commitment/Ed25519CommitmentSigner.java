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
import java.util.HexFormat;

/**
 * Ed25519 signer backed by the JDK 21 built-in {@code EdDSA} provider —
 * no external crypto dep. Verifier paired with
 * {@link #verify(CommitmentRecord, PublicKey)}.
 *
 * <h2>Key management</h2>
 * The signer owns a {@link KeyPair}. Three ways to construct:
 * <ul>
 *   <li>{@link #generate()} — mint a fresh keypair (testing, ephemeral workloads)</li>
 *   <li>{@link #Ed25519CommitmentSigner(KeyPair, String)} — pass pre-built
 *       keypair and key-id (production; operators load from KMS / HSM
 *       and pass the resolved keypair)</li>
 *   <li>{@link #Ed25519CommitmentSigner(PrivateKey, PublicKey, String)}
 *       — accept raw keys split</li>
 * </ul>
 *
 * <p>The signer never persists keys; operators manage rotation / rollover
 * / retirement at their layer and reinstall the signer on the
 * {@code JournalingAgentFleet} when the keypair changes.</p>
 */
public final class Ed25519CommitmentSigner implements CommitmentSigner {

    private static final Logger logger = LoggerFactory.getLogger(Ed25519CommitmentSigner.class);

    /** JDK standard name for the Ed25519 signature algorithm. */
    public static final String SCHEME = "Ed25519";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    /**
     * @param keyPair the Ed25519 keypair. The JDK's {@code KeyPairGenerator}
     *                returns EdDSA keys when initialised with {@code "Ed25519"}.
     * @param keyId   public identifier emitted on
     *                {@link CommitmentRecord.Proof#keyId()}. Operators use
     *                a DID, fingerprint, or application-specific ID.
     */
    public Ed25519CommitmentSigner(KeyPair keyPair, String keyId) {
        this(keyPair.getPrivate(), keyPair.getPublic(), keyId);
    }

    public Ed25519CommitmentSigner(PrivateKey privateKey, PublicKey publicKey, String keyId) {
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

    /** Mint a fresh Ed25519 keypair + auto-derive a fingerprint keyId. */
    public static Ed25519CommitmentSigner generate() {
        try {
            var gen = KeyPairGenerator.getInstance(SCHEME);
            var pair = gen.generateKeyPair();
            var fingerprint = fingerprint(pair.getPublic());
            return new Ed25519CommitmentSigner(pair, fingerprint);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "JDK does not ship Ed25519 — requires JDK 15+", e);
        }
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public CommitmentRecord.Proof sign(CommitmentRecord record) {
        var payload = record.canonicalPayload().getBytes(StandardCharsets.UTF_8);
        try {
            var signature = Signature.getInstance(SCHEME);
            signature.initSign(privateKey);
            signature.update(payload);
            var sigBytes = signature.sign();
            var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
            return new CommitmentRecord.Proof(SCHEME, keyId, encoded, Instant.now());
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            logger.error("Ed25519 signing failed for record {}: {}", record.id(), e.toString());
            return CommitmentRecord.Proof.UNSIGNED;
        }
    }

    /** Expose the public key so callers can ship it to verifiers. */
    public PublicKey publicKey() {
        return publicKey;
    }

    /**
     * Verify a {@link CommitmentRecord}'s signature against a public key.
     * Returns {@code true} only when the proof scheme is {@code Ed25519},
     * the signature decodes cleanly, and the bytes match the canonical
     * payload.
     */
    public static boolean verify(CommitmentRecord record, PublicKey publicKey) {
        if (record == null || record.proof() == null
                || !SCHEME.equals(record.proof().scheme())) {
            return false;
        }
        try {
            var sigBytes = Base64.getUrlDecoder().decode(record.proof().signature());
            var signature = Signature.getInstance(SCHEME);
            signature.initVerify(publicKey);
            signature.update(record.canonicalPayload().getBytes(StandardCharsets.UTF_8));
            return signature.verify(sigBytes);
        } catch (IllegalArgumentException | NoSuchAlgorithmException
                 | InvalidKeyException | SignatureException e) {
            logger.debug("Ed25519 verification failed for record {}: {}",
                    record.id(), e.toString());
            return false;
        }
    }

    /** SHA-256 fingerprint of the public key's encoded form — stable across JVMs. */
    static String fingerprint(PublicKey key) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "ed25519:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "ed25519:" + Integer.toHexString(key.hashCode());
        }
    }
}
