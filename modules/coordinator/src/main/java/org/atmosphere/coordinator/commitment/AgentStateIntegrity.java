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

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;

/**
 * Deprecated forwarder to
 * {@link org.atmosphere.ai.state.seal.AgentStateIntegrity}, which relocated
 * to {@code atmosphere-ai} so the file-backed
 * {@code FileSystemAgentState} could become its production consumer (the
 * opt-in state seal — {@code org.atmosphere.ai.state.seal.AgentStateSealer})
 * without this module on the classpath. This class keeps the released
 * coordinates compiling; it adds no behavior of its own.
 *
 * @deprecated use {@link org.atmosphere.ai.state.seal.AgentStateIntegrity}
 */
@Deprecated(since = "4.0.66")
public final class AgentStateIntegrity {

    private final org.atmosphere.ai.state.seal.AgentStateIntegrity delegate;

    public AgentStateIntegrity(KeyPair keyPair, String keyId) {
        this.delegate = new org.atmosphere.ai.state.seal.AgentStateIntegrity(keyPair, keyId);
    }

    public AgentStateIntegrity(PrivateKey privateKey, PublicKey publicKey, String keyId) {
        this.delegate = new org.atmosphere.ai.state.seal.AgentStateIntegrity(
                privateKey, publicKey, keyId);
    }

    private AgentStateIntegrity(org.atmosphere.ai.state.seal.AgentStateIntegrity delegate) {
        this.delegate = delegate;
    }

    /** Mint a fresh Ed25519 keypair + derive a fingerprint keyId. */
    public static AgentStateIntegrity generate() {
        return new AgentStateIntegrity(
                org.atmosphere.ai.state.seal.AgentStateIntegrity.generate());
    }

    /** See {@link org.atmosphere.ai.state.seal.AgentStateIntegrity#seal}. */
    public Seal seal(String key, String content) {
        return Seal.from(delegate.seal(key, content));
    }

    /** Instance twin of {@link #verify(String, String, Seal, PublicKey)}. */
    public boolean verify(String key, String content, Seal seal) {
        return verify(key, content, seal, delegate.publicKey());
    }

    /**
     * See
     * {@link org.atmosphere.ai.state.seal.AgentStateIntegrity#verify(String,
     * String, org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal, PublicKey)}.
     */
    public static boolean verify(String key, String content, Seal seal, PublicKey publicKey) {
        if (seal == null) {
            return false;
        }
        return org.atmosphere.ai.state.seal.AgentStateIntegrity.verify(
                key, content, seal.toDelegate(), publicKey);
    }

    public PublicKey publicKey() {
        return delegate.publicKey();
    }

    public String keyId() {
        return delegate.keyId();
    }

    /**
     * Integrity seal — mirrors
     * {@link org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal} for the
     * released coordinates.
     *
     * @deprecated use {@link org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal}
     */
    @Deprecated(since = "4.0.66")
    public record Seal(String scheme, String keyId, String signature, Instant createdAt) {

        public static final Seal EMPTY = new Seal("", "", "", Instant.EPOCH);

        public Seal {
            scheme = scheme == null ? "" : scheme;
            keyId = keyId == null ? "" : keyId;
            signature = signature == null ? "" : signature;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        }

        public boolean isPresent() {
            return !this.equals(EMPTY) && !signature.isEmpty();
        }

        static Seal from(org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal seal) {
            if (seal == null || !seal.isPresent()) {
                return EMPTY;
            }
            return new Seal(seal.scheme(), seal.keyId(), seal.signature(), seal.createdAt());
        }

        org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal toDelegate() {
            // A converted EMPTY still fails verification in the delegate via
            // its blank-scheme check, preserving the fail-by-construction
            // sentinel semantics across the forwarding boundary.
            return new org.atmosphere.ai.state.seal.AgentStateIntegrity.Seal(
                    scheme, keyId, signature, createdAt);
        }
    }
}
