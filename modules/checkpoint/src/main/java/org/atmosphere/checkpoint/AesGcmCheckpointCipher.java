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
package org.atmosphere.checkpoint;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM {@link CheckpointCipher} — the same scheme the framework's
 * encrypted credential store uses ({@code AES/GCM/NoPadding}, 96-bit random
 * IV per value, 128-bit tag, 256-bit key), applied to checkpoint columns.
 *
 * <h2>Envelope</h2>
 *
 * Ciphertext is stored as {@code enc:v1:<base64(iv || ciphertext)>}. The
 * prefix serves two purposes: (a) <b>legacy migration</b> — a value without
 * the prefix is a pre-cipher plaintext row and passes through
 * {@link #decrypt} unchanged, so enabling encryption never bricks existing
 * checkpoints; (b) <b>versioning</b> — a future scheme bumps the prefix and
 * can route per version (key rotation).
 *
 * <h2>Key supply</h2>
 *
 * The key must be 32 bytes of cryptographic randomness from a source durable
 * across restarts (KMS, OS keychain, a base64 secret) — a fresh per-process
 * key could never decrypt prior snapshots. Key loss means permanent snapshot
 * loss; decryption of tampered data fails closed with
 * {@link IllegalStateException} (Correctness Invariant #6), never a garbled
 * or silently-dropped snapshot.
 *
 * <h2>IV discipline</h2>
 *
 * A fresh random 96-bit IV is generated per encrypted value and stored in the
 * envelope. IV reuse under one key is catastrophic for GCM — never cache or
 * share IVs.
 */
public final class AesGcmCheckpointCipher implements CheckpointCipher {

    /** Envelope prefix marking a v1 AES-GCM value. */
    public static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;          // 96 bits — NIST-recommended
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;         // 256-bit AES key

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * Construct with an explicit 256-bit key. The byte array is copied;
     * callers may zero their copy afterward.
     *
     * @throws IllegalArgumentException if the key is not 32 bytes long
     */
    public AesGcmCheckpointCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("checkpoint cipher key must be " + KEY_BYTES
                    + " bytes; got " + (keyBytes == null ? "null" : keyBytes.length));
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    /**
     * Construct from a base64-encoded 256-bit key — the shape a key lands in
     * from an environment variable or secret manager.
     */
    public static AesGcmCheckpointCipher fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("base64Key must not be blank");
        }
        return new AesGcmCheckpointCipher(Base64.getDecoder().decode(base64Key.trim()));
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        var iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Checkpoint encryption failed", e);
        }
    }

    @Override
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext row written before the cipher was enabled —
            // pass through so turning encryption on never bricks history.
            return stored;
        }
        try {
            var envelope = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (envelope.length <= IV_BYTES) {
                throw new IllegalStateException("Checkpoint ciphertext too short");
            }
            var iv = new byte[IV_BYTES];
            System.arraycopy(envelope, 0, iv, 0, IV_BYTES);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var plaintext = cipher.doFinal(envelope, IV_BYTES, envelope.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Tampered ciphertext or wrong key: fail closed (Invariant #6) —
            // a garbled snapshot silently loaded would corrupt a resume.
            throw new IllegalStateException("Checkpoint decryption failed — wrong key "
                    + "or tampered data", e);
        }
    }
}
