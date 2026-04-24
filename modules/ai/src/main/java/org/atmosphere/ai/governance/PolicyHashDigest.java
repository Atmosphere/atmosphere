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
package org.atmosphere.ai.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Stable SHA-256 digest of a policy's enforceable content. Answers the
 * supply-chain question "is the policy evaluating right now the same one
 * we loaded at boot?" — critical when YAML is mounted from a writable
 * volume, shared config server, or fetched from a URL.
 *
 * <p>Two inputs get hashed:</p>
 * <ul>
 *   <li><b>Identity prefix</b> — {@code name|source|version\n} so the same
 *       content hash from two differently-named policies doesn't collide.</li>
 *   <li><b>Raw artifact bytes</b> when available (YAML source, Rego source,
 *       serialized matrix). For code-only policies the digest falls back to
 *       the identity prefix alone — callers at least get drift detection on
 *       the {@code version} field.</li>
 * </ul>
 *
 * <p>Result is a 64-char hex string prefixed with {@code sha256:}. Same
 * format used in audit entries so operators can diff two digests with a
 * string compare.</p>
 *
 * <p>Not a signature — nothing here proves <i>who</i> authored the policy.
 * Pair with the existing {@code CommitmentRecord} Ed25519 signing surface
 * if you need authorship proof.</p>
 */
public final class PolicyHashDigest {

    /** Algorithm identifier prefix on every digest. */
    public static final String ALGO_PREFIX = "sha256:";

    private PolicyHashDigest() { }

    /**
     * Digest a policy by identity alone. Useful for code-defined policies
     * whose enforceable content is compiled in and can't be hashed at runtime.
     */
    public static String forIdentity(GovernancePolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return digest(identityPrefix(policy).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Digest a policy with an auxiliary byte-oriented artifact — YAML source,
     * Rego source, serialized rule matrix. The identity prefix is still
     * included so the digest binds artifact bytes to the named policy.
     */
    public static String forPolicy(GovernancePolicy policy, byte[] artifact) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(artifact, "artifact must not be null");
        var prefix = identityPrefix(policy).getBytes(StandardCharsets.UTF_8);
        var combined = new byte[prefix.length + artifact.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(artifact, 0, combined, prefix.length, artifact.length);
        return digest(combined);
    }

    /** Digest a policy and an artifact passed as a string (UTF-8 encoded). */
    public static String forPolicy(GovernancePolicy policy, String artifact) {
        return forPolicy(policy, artifact == null
                ? new byte[0]
                : artifact.getBytes(StandardCharsets.UTF_8));
    }

    /** True when {@code observed} matches {@code expected}. Null-safe. */
    public static boolean matches(String expected, String observed) {
        if (expected == null || observed == null) return false;
        return expected.equalsIgnoreCase(observed);
    }

    private static String identityPrefix(GovernancePolicy policy) {
        // Newline-terminated so empty version / source can't collide with a
        // different split between name and source.
        return policy.name() + '|' + policy.source() + '|' + policy.version() + '\n';
    }

    private static String digest(byte[] bytes) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var hash = md.digest(bytes);
            return ALGO_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java runtime; reach-here is a
            // classpath pathology, not a user-fixable misconfiguration.
            throw new IllegalStateException("SHA-256 not available in this JRE", e);
        }
    }
}
