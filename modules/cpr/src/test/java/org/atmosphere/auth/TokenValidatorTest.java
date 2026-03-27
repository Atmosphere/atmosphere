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
package org.atmosphere.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TokenValidatorTest {

    @Test
    public void testValidWithNameAndClaims() {
        var valid = new TokenValidator.Valid("alice", Map.of("role", "admin"));
        assertEquals("alice", valid.principal().getName());
        assertEquals("admin", valid.claims().get("role"));
        assertInstanceOf(SimplePrincipal.class, valid.principal());
    }

    @Test
    public void testValidWithNameOnly() {
        var valid = new TokenValidator.Valid("bob");
        assertEquals("bob", valid.principal().getName());
        assertTrue(valid.claims().isEmpty());
    }

    @Test
    public void testValidWithPrincipalAndClaims() {
        var principal = new SimplePrincipal("charlie");
        var claims = Map.<String, Object>of("exp", 12345L);
        var valid = new TokenValidator.Valid(principal, claims);
        assertSame(principal, valid.principal());
        assertEquals(12345L, valid.claims().get("exp"));
    }

    @Test
    public void testInvalidWithReason() {
        var invalid = new TokenValidator.Invalid("Bad signature");
        assertEquals("Bad signature", invalid.reason());
    }

    @Test
    public void testInvalidDefault() {
        var invalid = new TokenValidator.Invalid();
        assertEquals("Invalid token", invalid.reason());
    }

    @Test
    public void testExpiredWithReason() {
        var expired = new TokenValidator.Expired("Token expired at 2026-03-13T10:00:00Z");
        assertEquals("Token expired at 2026-03-13T10:00:00Z", expired.reason());
    }

    @Test
    public void testExpiredDefault() {
        var expired = new TokenValidator.Expired();
        assertEquals("Token expired", expired.reason());
    }

    @Test
    public void testResultIsSealed() {
        // Verify all three result types implement Result
        assertInstanceOf(TokenValidator.Result.class, new TokenValidator.Valid("a"));
        assertInstanceOf(TokenValidator.Result.class, new TokenValidator.Invalid());
        assertInstanceOf(TokenValidator.Result.class, new TokenValidator.Expired());
    }

    @Test
    public void testPatternMatchingOnResult() {
        TokenValidator.Result result = new TokenValidator.Valid("test");
        var matched = switch (result) {
            case TokenValidator.Valid v -> "valid:" + v.principal().getName();
            case TokenValidator.Invalid i -> "invalid:" + i.reason();
            case TokenValidator.Expired e -> "expired:" + e.reason();
        };
        assertEquals("valid:test", matched);
    }

    @Test
    public void testFunctionalInterface() {
        TokenValidator validator = token -> {
            if ("good".equals(token)) {
                return new TokenValidator.Valid("user1");
            }
            if ("old".equals(token)) {
                return new TokenValidator.Expired();
            }
            return new TokenValidator.Invalid();
        };

        assertInstanceOf(TokenValidator.Valid.class, validator.validate("good"));
        assertInstanceOf(TokenValidator.Expired.class, validator.validate("old"));
        assertInstanceOf(TokenValidator.Invalid.class, validator.validate("bad"));
    }
}
