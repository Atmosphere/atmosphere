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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.auth.TokenValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The sample's identity channel: a sign-in token resolves to the principal that
 * long-term memory keys on.
 *
 * <p>This replaces a test that hand-built an {@code AtmosphereRequest} already
 * carrying a {@code ?user=} parameter and asserted an {@code @Ready} hook copied
 * it to {@code ai.userId}. That test passed for eleven days while the feature was
 * dead: the Console forwards only {@code token} onto the transport, so the
 * parameter never arrived, every visitor collapsed onto one {@code demo-user}
 * bucket, and each user was told the previous user's facts. Constructing the
 * input the production path never produces is how a green test hides a broken
 * feature — so this pins the channel the Console actually uses.</p>
 */
class PrimaryAssistantUserIdTest {

    private final TokenValidator validator = new PersonalAssistantApplication().demoUserTokenValidator();

    @Test
    void tokenResolvesToAPrincipalNamedForThatUser() {
        var alice = validator.validate("alice");
        assertInstanceOf(TokenValidator.Valid.class, alice,
                "a non-blank sign-in token must resolve to a principal");
        assertEquals("alice", ((TokenValidator.Valid) alice).principal().getName(),
                "long-term memory keys on this principal — it must be the signed-in user");
    }

    @Test
    void differentTokensResolveToDifferentPrincipals() {
        var alice = ((TokenValidator.Valid) validator.validate("alice")).principal().getName();
        var bob = ((TokenValidator.Valid) validator.validate("bob")).principal().getName();
        assertNotEquals(alice, bob,
                "two users must not share a memory bucket — this is the cross-user leak "
                        + "the 2026-08-31 sweep found, where both resolved to 'demo-user'");
    }

    @Test
    void anonymousIsRejectedRatherThanBucketedTogether() {
        // Refusing is the point: a shared fallback identity for every anonymous
        // visitor is precisely what leaked one user's facts to the next.
        assertInstanceOf(TokenValidator.Invalid.class, validator.validate(null),
                "an anonymous caller must not resolve to a shared identity");
        assertInstanceOf(TokenValidator.Invalid.class, validator.validate("   "),
                "a blank token must not resolve to a shared identity");
    }
}
