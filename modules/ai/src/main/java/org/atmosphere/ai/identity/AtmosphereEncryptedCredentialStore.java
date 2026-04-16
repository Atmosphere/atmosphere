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
package org.atmosphere.ai.identity;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CredentialStore} backed by AES-GCM encryption with a 256-bit master
 * key. The ciphertext is held in memory alongside a random 96-bit IV per
 * entry so plaintext never sits in the JVM heap longer than one
 * encrypt/decrypt hop.
 *
 * <h2>Master key supply</h2>
 *
 * The master key is passed at construction time. In production the caller
 * should derive it from an OS keychain (macOS Keychain, Linux Secret Service,
 * Windows Credential Manager) or from a KMS. The store itself does not
 * prescribe how the key arrives — just that it must be 32 bytes of
 * cryptographic randomness. Per the v0.6 plan open question: AES-GCM with
 * an OS-keychain-derived key, no plaintext fallback.
 *
 * <h2>Boundary safety</h2>
 *
 * Every {@link #put(String, String, String)} generates a fresh random IV.
 * IV reuse under the same key would be catastrophic (AES-GCM loses all
 * confidentiality on IV collision), so this is non-negotiable — never share
 * IVs across entries.
 *
 * <h2>Durability</h2>
 *
 * This implementation is memory-only. A persistent variant that writes
 * ciphertext to disk or a database belongs in a downstream module —
 * intentionally out of scope here so we do not conflate encryption with
 * storage policy.
 */
public final class AtmosphereEncryptedCredentialStore implements CredentialStore {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;          // 96 bits — NIST-recommended
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;          // 256-bit AES key

    private final SecretKey masterKey;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Construct a store with an explicit 256-bit master key. The byte array
     * is copied; callers may zero their copy afterward.
     *
     * @throws IllegalArgumentException if the key is not 32 bytes long
     */
    public AtmosphereEncryptedCredentialStore(byte[] masterKeyBytes) {
        if (masterKeyBytes == null || masterKeyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "master key must be " + KEY_BYTES + " bytes; got "
                            + (masterKeyBytes == null ? "null" : masterKeyBytes.length));
        }
        this.masterKey = new SecretKeySpec(masterKeyBytes.clone(), "AES");
    }

    /**
     * Generate a fresh 256-bit master key and wrap it in a store. The raw
     * key bytes are unreachable after construction — suitable for ephemeral
     * / single-process use. Production callers should derive the key from a
     * durable source (KMS, OS keychain) so restarts can decrypt prior
     * entries.
     */
    public static AtmosphereEncryptedCredentialStore withFreshKey() {
        var key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new AtmosphereEncryptedCredentialStore(key);
    }

    @Override
    public Optional<String> get(String userId, String key) {
        var entry = entries.get(composeKey(userId, key));
        if (entry == null) {
            return Optional.empty();
        }
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_BITS, entry.iv));
            var plaintext = cipher.doFinal(entry.ciphertext);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // Decryption failure is a hard error — the ciphertext has been
            // tampered with or the master key is wrong. Fail closed
            // (Correctness Invariant #6) rather than silently returning
            // empty, which would leak the failure mode as "no credential".
            throw new IllegalStateException(
                    "Credential decryption failed for " + userId + "/" + key, e);
        }
    }

    @Override
    public void put(String userId, String key, String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("secret must not be null");
        }
        var iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            var ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            entries.put(composeKey(userId, key), new Entry(iv, ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Credential encryption failed for " + userId + "/" + key, e);
        }
    }

    @Override
    public void delete(String userId, String key) {
        entries.remove(composeKey(userId, key));
    }

    @Override
    public Optional<String> identifier(String userId, String key) {
        // Avoid decrypting the secret just to render a prefix — derive an
        // identifier from a stable hash of the ciphertext IV instead. That
        // changes each time the secret is rotated, which is the behavior we
        // want: "is this the same secret as before?" admin answer.
        var entry = entries.get(composeKey(userId, key));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of("iv:" + Base64.getEncoder()
                .withoutPadding()
                .encodeToString(entry.iv)
                .substring(0, 8));
    }

    @Override
    public String name() {
        return "atmosphere-encrypted";
    }

    @Override
    public String toString() {
        return "AtmosphereEncryptedCredentialStore[entries=" + entries.size() + "]";
    }

    private static String composeKey(String userId, String key) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return userId + "::" + key;
    }

    private record Entry(byte[] iv, byte[] ciphertext) {
    }
}
