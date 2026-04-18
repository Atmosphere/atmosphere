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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SPI for per-user credentials. Every secret touching Atmosphere flows
 * through this store — LLM provider API keys, per-user MCP tokens, external
 * tool credentials.
 *
 * <h2>Pluggability</h2>
 *
 * Three built-in implementations ship:
 * <ul>
 *   <li>{@link InMemoryCredentialStore} — unencrypted, for tests and
 *       short-lived development. Never ship this in production.</li>
 *   <li>{@link AtmosphereEncryptedCredentialStore} — AES-GCM-encrypted
 *       storage with a configurable master key. The default for production
 *       unless a platform-specific store is available.</li>
 *   <li>OS keychain and OAuth-delegated variants are pluggable via
 *       additional implementations; the SPI is intentionally minimal so
 *       third parties can ship their own.</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 *
 * <ul>
 *   <li>Secrets are never logged. Implementations must not expose the raw
 *       secret via {@code toString()}.</li>
 *   <li>{@link #put(String, String, String)} is idempotent per
 *       {@code (userId, key)} — a second call replaces the prior value.</li>
 *   <li>{@link #delete(String, String)} is a no-op when no such secret
 *       exists; this matches Correctness Invariant #2 (terminal paths).</li>
 * </ul>
 */
public interface CredentialStore {

    /**
     * Retrieve the plaintext secret for a given user + key. Returns
     * {@link Optional#empty()} when the credential does not exist.
     */
    Optional<String> get(String userId, String key);

    /** Store a secret under {@code (userId, key)}, replacing any prior value. */
    void put(String userId, String key, String secret);

    /** Remove a stored secret. No-op when absent. */
    void delete(String userId, String key);

    /**
     * A short, log-safe identifier for the secret ({@code "cred-abc123..."})
     * surfaced in admin UIs so operators can see which credential was used
     * without the secret itself leaking. Returns {@link Optional#empty()}
     * when absent.
     *
     * <p>The default implementation returns an HMAC-SHA256 of the secret
     * keyed with a per-process salt — deterministic within one JVM (so the
     * same secret groups all its log lines) but non-reversible and free of
     * raw secret bytes (Correctness Invariant #6).</p>
     */
    default Optional<String> identifier(String userId, String key) {
        return get(userId, key).map(Identifiers::derive);
    }

    /** Name of this store implementation (for admin inspection). */
    String name();

    /**
     * HMAC-keyed identifier derivation. Holds a per-process salt generated
     * once so ids remain stable for a given JVM lifetime but cannot be
     * precomputed or correlated across processes.
     */
    final class Identifiers {

        private static final byte[] PROCESS_SALT = new byte[16];

        static {
            new SecureRandom().nextBytes(PROCESS_SALT);
        }

        private Identifiers() {
        }

        /**
         * Derive a {@code "cred-<hex8>"} identifier from the given secret.
         * The prior substring-prefix approach leaked raw bytes of the secret
         * (critical for {@code sk-...}-style API keys), so every non-empty
         * secret now goes through HMAC-SHA256 with {@link #PROCESS_SALT}.
         */
        public static String derive(String secret) {
            try {
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(PROCESS_SALT, "HmacSHA256"));
                var digest = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
                // First 8 bytes is enough to group log lines without
                // offering a brute-force preimage target of any value.
                return "cred-" + HexFormat.of().formatHex(digest, 0, 8);
            } catch (java.security.GeneralSecurityException e) {
                // HmacSHA256 is mandatory in every JDK — absence is fatal.
                throw new IllegalStateException(
                        "HmacSHA256 is not available in this JVM", e);
            }
        }
    }
}
