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
package org.atmosphere.samples.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.atmosphere.auth.TokenValidator;

/**
 * A self-contained demo {@link TokenValidator} that performs <b>real</b>
 * HMAC-SHA256 verification — no external identity provider and no extra
 * dependency, just the JDK. It is wired in only under the {@code auth} Spring
 * profile (see {@code application-auth.properties}), so the sample's default
 * out-of-box posture is unchanged.
 *
 * <p>Token format: {@code <subject>.<base64url(HMAC-SHA256(subject, secret))>}.
 * The signature is verified in constant time; a token whose signature does not
 * match the shared secret is rejected.</p>
 *
 * <p><b>This is a demonstration of the {@code TokenValidator} SPI, not a
 * production token scheme.</b> For production, validate real OIDC/JWT access
 * tokens — the idiomatic Spring path is {@code spring-boot-starter-oauth2-resource-server}
 * with {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}; that filter
 * sets the servlet principal, which the MCP authorization gate also honors.</p>
 */
public class DemoHmacTokenValidator implements TokenValidator {

    /** Shared secret. Overridable via the {@code MCP_DEMO_AUTH_SECRET} env var. */
    private static final String SECRET = System.getenv()
            .getOrDefault("MCP_DEMO_AUTH_SECRET", "atmosphere-mcp-demo-secret");

    @Override
    public Result validate(String token) {
        if (token == null) {
            return new Invalid("no token");
        }
        var dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return new Invalid("malformed token");
        }
        var subject = token.substring(0, dot);
        var presented = token.substring(dot + 1);
        var expected = sign(subject);
        if (expected == null) {
            return new Invalid("signing unavailable");
        }
        // Constant-time comparison to avoid leaking the signature byte-by-byte.
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII))) {
            return new Invalid("bad signature");
        }
        return new Valid(subject, Map.of("scope", "mcp:tools"));
    }

    /** Base64url(HMAC-SHA256(subject)). Returns {@code null} if HMAC is unavailable. */
    static String sign(String subject) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var sig = mac.doFinal(subject.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    /** Mint a valid demo token for {@code subject} (used by the README + tests). */
    static String mint(String subject) {
        return subject + "." + sign(subject);
    }
}
