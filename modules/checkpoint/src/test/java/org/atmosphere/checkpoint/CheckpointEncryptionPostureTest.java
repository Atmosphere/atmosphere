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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a store reports its own encryption posture truthfully.
 *
 * <p>Checkpoints hold whatever the agent put in its state — a secret passed as
 * a tool argument, PII from a conversation — and the store ships plaintext
 * unless an operator installs a cipher. That was mitigated only by a startup
 * WARN, which is the same "logs go unread" gap the permissive gateway default
 * had. Making the posture queryable lets an info surface show what is actually
 * true, and lets this test assert it (Correctness Invariant #5).</p>
 *
 * <p>The default is deliberately {@code false} on the interface: a store that
 * has not thought about encryption reports the conservative answer, so a new
 * backend cannot claim protection by omission.</p>
 */
class CheckpointEncryptionPostureTest {

    private static byte[] key() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void aPlaintextStoreReportsThatItIsNotEncrypted(@TempDir Path dir) throws Exception {
        var db = dir.resolve("plain.db");
        var store = new SqliteCheckpointStore(db, 5);
        try {
            store.start();
            assertFalse(store.encryptsAtRest(),
                    "a store with no cipher must not claim encryption — the whole point "
                            + "is that an operator can read the real posture back");
        } finally {
            store.stop();
            Files.deleteIfExists(db);
        }
    }

    @Test
    void anEncryptedStoreReportsThatItIsEncrypted(@TempDir Path dir) throws Exception {
        var db = dir.resolve("sealed.db");
        var store = new SqliteCheckpointStore(db, 5, new AesGcmCheckpointCipher(key()));
        try {
            store.start();
            assertTrue(store.encryptsAtRest(),
                    "an installed AES-GCM cipher must be visible as encrypted-at-rest");
        } finally {
            store.stop();
            Files.deleteIfExists(db);
        }
    }

    @Test
    void theInterfaceDefaultIsTheConservativeAnswer() {
        // A backend that never considered encryption must not report protection
        // it does not provide — false by omission, never true by omission.
        var naive = new CheckpointStore() {
            @Override public void start() { }

            @Override public void stop() { }

            @Override public <S> WorkflowSnapshot<S> save(WorkflowSnapshot<S> snapshot) {
                return snapshot;
            }

            @Override public <S> java.util.Optional<WorkflowSnapshot<S>> load(CheckpointId id) {
                return java.util.Optional.empty();
            }

            @Override public <S> WorkflowSnapshot<S> fork(CheckpointId sourceId, S newState) {
                throw new UnsupportedOperationException();
            }

            @Override public java.util.List<WorkflowSnapshot<?>> list(CheckpointQuery query) {
                return java.util.List.of();
            }

            @Override public boolean delete(CheckpointId id) {
                return false;
            }

            @Override public int deleteCoordination(String coordinationId) {
                return 0;
            }

            @Override public void addListener(CheckpointListener listener) { }

            @Override public void removeListener(CheckpointListener listener) { }
        };

        assertFalse(naive.encryptsAtRest(),
                "the SPI default must be false so protection is never claimed by silence");
    }
}
