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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the {@link CredentialStore#identifier(String, String)}
 * fix. The pre-fix default returned {@code secret.substring(0, 4) + "***"},
 * which leaked the first four bytes of the raw secret to logs and admin UIs —
 * catastrophic for deterministic-prefix keys like {@code sk-...} OpenAI
 * tokens (Correctness Invariant #6).
 */
class CredentialStoreIdentifierTest {

    @Test
    void identifierIsDeterministicWithinJvmForTheSameSecret() {
        var store = new InMemoryCredentialStore();
        store.put("alice", "openai", "sk-abcdef0123456789");

        var first = store.identifier("alice", "openai").orElseThrow();
        var second = store.identifier("alice", "openai").orElseThrow();
        assertEquals(first, second,
                "repeat derivations of the same secret must produce the same "
                        + "id — operators rely on this to group log lines");
    }

    @Test
    void distinctSecretsProduceDistinctIdentifiers() {
        var store = new InMemoryCredentialStore();
        store.put("alice", "openai", "sk-aaaaaaaaaaaaaaaa");
        store.put("bob", "openai", "sk-bbbbbbbbbbbbbbbb");

        var aliceId = store.identifier("alice", "openai").orElseThrow();
        var bobId = store.identifier("bob", "openai").orElseThrow();
        assertNotEquals(aliceId, bobId,
                "different secrets must derive to different ids — collisions "
                        + "would let an attacker confuse credentials");
    }

    @Test
    void identifierDoesNotLeakAnyPrefixOfTheRawSecret() {
        var store = new InMemoryCredentialStore();
        // Classic OpenAI-shaped key: the first bytes are predictable but the
        // bytes AFTER the sk- prefix are secret — the old impl leaked them.
        var secret = "sk-proj-abcdef0123456789ghijklmnopqrstuvwxyz";
        store.put("alice", "openai", secret);

        var id = store.identifier("alice", "openai").orElseThrow();

        // No substring of the raw secret (of any plausible prefix length)
        // may appear inside the id. The old impl would leak "sk-p" / "sk-pr"
        // etc.; the new impl must not.
        for (int len = 3; len <= secret.length(); len++) {
            var piece = secret.substring(0, len);
            assertFalse(id.contains(piece),
                    "identifier '" + id + "' leaked a " + len + "-byte prefix "
                            + "of the raw secret: '" + piece + "'");
        }
    }

    @Test
    void identifierUsesCredPrefixForGrep() {
        var store = new InMemoryCredentialStore();
        store.put("alice", "openai", "whatever");

        var id = store.identifier("alice", "openai").orElseThrow();
        assertTrue(id.startsWith("cred-"),
                "the 'cred-' prefix lets operators grep logs for credential ids "
                        + "without matching unrelated hex blobs, got: " + id);
    }

    @Test
    void shortSecretsAreHashedNotTruncatedToFourStars() {
        var store = new InMemoryCredentialStore();
        // A 2-char secret: the old impl returned "****" universally. The new
        // impl must still produce a distinguishing hash, not a constant.
        store.put("alice", "key", "ab");
        store.put("bob", "key", "cd");

        var a = store.identifier("alice", "key").orElseThrow();
        var b = store.identifier("bob", "key").orElseThrow();
        assertNotEquals(a, b,
                "short secrets must still derive to distinct ids — the old "
                        + "'****' constant hid that two users had different keys");
    }

    @Test
    void encryptedStoreOverrideIsPreserved() {
        // AtmosphereEncryptedCredentialStore already overrides identifier()
        // with an IV-based strategy — the fix must not regress that override.
        var store = AtmosphereEncryptedCredentialStore.withFreshKey();
        store.put("alice", "openai", "sk-abcdef");

        var id = store.identifier("alice", "openai").orElseThrow();
        assertTrue(id.startsWith("iv:"),
                "AtmosphereEncryptedCredentialStore's override must remain in "
                        + "effect — got: " + id);
    }
}
