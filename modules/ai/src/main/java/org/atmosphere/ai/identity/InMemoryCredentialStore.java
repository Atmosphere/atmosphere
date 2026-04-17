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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-encrypting {@link CredentialStore} for tests and short-lived
 * development. Production deployments should use
 * {@link AtmosphereEncryptedCredentialStore} or a platform-specific
 * implementation.
 *
 * <p>The store holds entries in memory only — restarting the JVM discards
 * everything. This is intentional: it keeps test runs hermetic.</p>
 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, String> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String userId, String key) {
        return Optional.ofNullable(entries.get(composeKey(userId, key)));
    }

    @Override
    public void put(String userId, String key, String secret) {
        entries.put(composeKey(userId, key), secret);
    }

    @Override
    public void delete(String userId, String key) {
        entries.remove(composeKey(userId, key));
    }

    @Override
    public String name() {
        return "in-memory";
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

    @Override
    public String toString() {
        // Secrets must never leak via toString. Report entry count only.
        return "InMemoryCredentialStore[entries=" + entries.size() + "]";
    }
}
