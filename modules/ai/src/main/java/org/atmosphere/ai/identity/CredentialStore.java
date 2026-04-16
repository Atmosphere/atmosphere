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

import java.util.Optional;

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
     * A short, log-safe identifier for the secret
     * ({@code "key-abc..."}) — surfaced in admin UIs so operators can see
     * which credential was used without the secret leaking into logs.
     * Returns {@link Optional#empty()} when absent.
     */
    default Optional<String> identifier(String userId, String key) {
        return get(userId, key).map(secret -> {
            if (secret.length() <= 4) {
                return "****";
            }
            return secret.substring(0, 4) + "***";
        });
    }

    /** Name of this store implementation (for admin inspection). */
    String name();
}
