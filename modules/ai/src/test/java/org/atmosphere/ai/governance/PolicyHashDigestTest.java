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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyHashDigestTest {

    private record FixedPolicy(String n, String s, String v) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return s; }
        @Override public String version() { return v; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
    }

    @Test
    void digestStartsWithAlgoPrefix() {
        var d = PolicyHashDigest.forIdentity(new FixedPolicy("scope.support", "test", "1"));
        assertTrue(d.startsWith(PolicyHashDigest.ALGO_PREFIX));
        assertEquals(PolicyHashDigest.ALGO_PREFIX.length() + 64, d.length(),
                "sha256 hex string is 64 chars after the algo prefix");
    }

    @Test
    void identityOnlyDigestIsStable() {
        var a = PolicyHashDigest.forIdentity(new FixedPolicy("scope.support", "test", "1"));
        var b = PolicyHashDigest.forIdentity(new FixedPolicy("scope.support", "test", "1"));
        assertEquals(a, b, "same identity → same digest");
    }

    @Test
    void differentVersionProducesDifferentDigest() {
        var v1 = PolicyHashDigest.forIdentity(new FixedPolicy("p", "test", "1"));
        var v2 = PolicyHashDigest.forIdentity(new FixedPolicy("p", "test", "2"));
        assertNotEquals(v1, v2, "version bump must change the digest");
    }

    @Test
    void differentSourceProducesDifferentDigest() {
        var s1 = PolicyHashDigest.forIdentity(new FixedPolicy("p", "yaml:/a", "1"));
        var s2 = PolicyHashDigest.forIdentity(new FixedPolicy("p", "yaml:/b", "1"));
        assertNotEquals(s1, s2);
    }

    @Test
    void identityPrefixIsTerminatedSoShiftedSplitsDontCollide() {
        // "ab|c|1" vs "a|bc|1" — without a terminator both yield the same bytes
        // for identity prefix. Test that version change after the pipe still
        // flips the hash.
        var a = PolicyHashDigest.forIdentity(new FixedPolicy("ab", "c", "1"));
        var b = PolicyHashDigest.forIdentity(new FixedPolicy("a", "bc", "1"));
        assertNotEquals(a, b,
                "policies with shifted pipe splits but same identity bytes must not collide");
    }

    @Test
    void artifactBytesBindToIdentity() {
        var policy = new FixedPolicy("p", "yaml:/x", "1");
        var d1 = PolicyHashDigest.forPolicy(policy, "rules: [...]");
        var d2 = PolicyHashDigest.forPolicy(policy, "rules: [OTHER]");
        assertNotEquals(d1, d2, "different artifact bytes → different digest");
    }

    @Test
    void sameArtifactDifferentIdentityProducesDifferentDigest() {
        var yaml = "rules: [...]";
        var d1 = PolicyHashDigest.forPolicy(new FixedPolicy("p1", "yaml:/x", "1"), yaml);
        var d2 = PolicyHashDigest.forPolicy(new FixedPolicy("p2", "yaml:/x", "1"), yaml);
        assertNotEquals(d1, d2,
                "identity prefix must bind the artifact to the named policy");
    }

    @Test
    void nullArtifactStringTreatedAsEmpty() {
        var policy = new FixedPolicy("p", "yaml:/x", "1");
        var withNull = PolicyHashDigest.forPolicy(policy, (String) null);
        var withEmpty = PolicyHashDigest.forPolicy(policy, "");
        assertEquals(withNull, withEmpty);
    }

    @Test
    void matchesHonorsHexCaseInsensitivity() {
        var d = "sha256:ABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789";
        var dLower = d.toLowerCase();
        assertTrue(PolicyHashDigest.matches(d, dLower));
        assertTrue(PolicyHashDigest.matches(dLower, d));
    }

    @Test
    void matchesReturnsFalseForDifferentDigests() {
        assertFalse(PolicyHashDigest.matches("sha256:abc", "sha256:def"));
        assertFalse(PolicyHashDigest.matches(null, "sha256:abc"));
        assertFalse(PolicyHashDigest.matches("sha256:abc", null));
    }

    @Test
    void nullPolicyRejected() {
        assertThrows(NullPointerException.class, () -> PolicyHashDigest.forIdentity(null));
        assertThrows(NullPointerException.class, () -> PolicyHashDigest.forPolicy(null, "x"));
    }

    @Test
    void nullArtifactByteArrayRejected() {
        var policy = new FixedPolicy("p", "test", "1");
        assertThrows(NullPointerException.class,
                () -> PolicyHashDigest.forPolicy(policy, (byte[]) null));
    }
}
