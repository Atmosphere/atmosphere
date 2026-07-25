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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Tier-1 plaintext-capture P1 (checkpoint half):
 * the durable stores persisted {@code state_json} / {@code metadata_json} as
 * plaintext. With an {@link AesGcmCheckpointCipher} the columns are ciphertext
 * at rest, loads round-trip (checkpoints are resumed — the transform must be
 * reversible), legacy plaintext rows stay readable after enabling the cipher,
 * and tampered ciphertext fails closed.
 */
class CheckpointCipherTest {

    private static final String SECRET = "api-key-hunter2-and-alice@example.com";

    @TempDir
    Path tmp;

    private static byte[] key() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static WorkflowSnapshot<Map<String, String>> snapshot(String id) {
        return WorkflowSnapshot.<Map<String, String>>builder()
                .id(CheckpointId.of(id))
                .coordinationId("coord-1")
                .agentName("agent-1")
                .state(Map.of("secret", SECRET))
                .metadata(Map.of("note", "holds " + SECRET))
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void cipherRoundTripsAndDbHoldsOnlyCiphertext() throws Exception {
        var db = tmp.resolve("enc.db");
        var store = new SqliteCheckpointStore(db, 100, new AesGcmCheckpointCipher(key()));
        store.start();
        try {
            store.save(snapshot("cp-1"));

            // (a) Reversibility: the loaded state round-trips byte-exact.
            var loaded = store.<Map<String, String>>load(CheckpointId.of("cp-1")).orElseThrow();
            assertEquals(SECRET, loaded.state().get("secret"));
            assertEquals("holds " + SECRET, loaded.metadata().get("note"));

            // (b) At rest: a raw SELECT must see ciphertext, never the secret.
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT state_json, metadata_json FROM checkpoints")) {
                assertTrue(rs.next());
                var stateAtRest = rs.getString(1);
                var metaAtRest = rs.getString(2);
                assertFalse(stateAtRest.contains(SECRET),
                        "state_json must be ciphertext at rest: " + stateAtRest);
                assertFalse(metaAtRest.contains(SECRET),
                        "metadata_json must be ciphertext at rest: " + metaAtRest);
                assertTrue(stateAtRest.startsWith(AesGcmCheckpointCipher.PREFIX));
            }
        } finally {
            store.stop();
        }
    }

    @Test
    void legacyPlaintextRowsStayReadableAfterEnablingTheCipher() {
        var db = tmp.resolve("migrate.db");
        // Write with the plaintext default (the pre-cipher deployment)...
        var plain = new SqliteCheckpointStore(db, 100);
        plain.start();
        plain.save(snapshot("cp-legacy"));
        plain.stop();

        // ...then reopen WITH the cipher: the legacy row must still load
        // (enabling encryption never bricks prior checkpoints).
        var encrypted = new SqliteCheckpointStore(db, 100, new AesGcmCheckpointCipher(key()));
        encrypted.start();
        try {
            var loaded = encrypted.<Map<String, String>>load(
                    CheckpointId.of("cp-legacy")).orElseThrow();
            assertEquals(SECRET, loaded.state().get("secret"));
        } finally {
            encrypted.stop();
        }
    }

    @Test
    void wrongKeyFailsClosedInsteadOfReturningGarbage() {
        var db = tmp.resolve("tamper.db");
        var store = new SqliteCheckpointStore(db, 100, new AesGcmCheckpointCipher(key()));
        store.start();
        store.save(snapshot("cp-2"));
        store.stop();

        // Reopen with a DIFFERENT key: the load must throw, never silently
        // return a garbled/empty snapshot (Invariant #6, fail closed).
        var wrongKey = new SqliteCheckpointStore(db, 100, new AesGcmCheckpointCipher(key()));
        wrongKey.start();
        try {
            assertThrows(IllegalStateException.class,
                    () -> wrongKey.load(CheckpointId.of("cp-2")));
        } finally {
            wrongKey.stop();
        }
    }

    @Test
    void defaultStoreStaysPlaintext() throws Exception {
        // No cipher: byte-identical to the pre-fix behavior (opt-in posture).
        var db = tmp.resolve("plain.db");
        var store = new SqliteCheckpointStore(db, 100);
        store.start();
        try {
            store.save(snapshot("cp-3"));
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT state_json FROM checkpoints")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains(SECRET),
                        "the default store persists plaintext exactly as before");
            }
        } finally {
            store.stop();
        }
    }

    @Test
    void base64FactoryRoundTrips() {
        var raw = key();
        var cipher = AesGcmCheckpointCipher.fromBase64(
                java.util.Base64.getEncoder().encodeToString(raw));
        var roundTripped = cipher.decrypt(cipher.encrypt("hello world"));
        assertEquals("hello world", roundTripped);
    }
}
