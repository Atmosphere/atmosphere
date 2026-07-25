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

/**
 * Encryption-at-rest hook for durable checkpoint stores — the fix for the
 * Tier-1 plaintext-capture P1: {@code SqliteCheckpointStore} and
 * {@code PostgresCheckpointStore} persist agent state and metadata as
 * plaintext JSON, so secrets or PII inside a workflow snapshot land unmasked
 * on disk. A cipher installed at store construction encrypts
 * {@code state_json} / {@code metadata_json} on save and decrypts on load.
 *
 * <p><b>Opt-in.</b> The default {@link #NONE} is the identity — plaintext,
 * exactly as before (the durable stores log a one-line startup WARN so the
 * posture is visible). Checkpoints are <em>resumed</em>, so the transform must
 * be reversible — encryption, never redaction (a lossy mask would corrupt
 * {@code load()}/{@code fork()}). Ship the bundled
 * {@link AesGcmCheckpointCipher} with a durable key, or implement this
 * interface over a KMS.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code decrypt(encrypt(x)).equals(x)} for every non-null {@code x}.</li>
 *   <li>Thread-safe.</li>
 *   <li>{@code null} passes through both directions (columns are nullable).</li>
 *   <li>Decryption of tampered/undecryptable ciphertext MUST throw — fail
 *       closed (Correctness Invariant #6), never return a garbled snapshot.</li>
 *   <li>Implementations should pass legacy plaintext rows through unchanged
 *       on decrypt so enabling a cipher does not brick prior checkpoints (see
 *       {@link AesGcmCheckpointCipher}'s envelope prefix).</li>
 * </ul>
 */
public interface CheckpointCipher {

    /** Encrypt one column value; {@code null} in → {@code null} out. */
    String encrypt(String plaintext);

    /** Decrypt one column value; {@code null} in → {@code null} out. */
    String decrypt(String stored);

    /** Identity cipher — the default: columns persist as plaintext. */
    CheckpointCipher NONE = new CheckpointCipher() {
        @Override
        public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override
        public String decrypt(String stored) {
            return stored;
        }
    };
}
