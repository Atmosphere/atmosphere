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

import java.security.Principal;
import java.util.Map;

/**
 * SPI for validating authentication tokens on Atmosphere connections.
 * Implementations are responsible for verifying tokens (JWT, opaque, session-based, etc.)
 * and returning structured results that the {@link org.atmosphere.interceptor.AuthInterceptor}
 * uses to allow, deny, or trigger refresh flows.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * framework.interceptor(new AuthInterceptor(token -> {
 *     var claims = myJwtLib.verify(token);
 *     return new TokenValidator.Valid(claims.getSubject(), claims);
 * }));
 * }</pre>
 *
 * @since 4.0
 */
@FunctionalInterface
public interface TokenValidator {

    /**
     * Validate the given token string.
     *
     * @param token the raw token extracted from the request (header or query param)
     * @return a {@link Result} indicating the outcome
     */
    Result validate(String token);

    /**
     * The result of token validation.
     */
    sealed interface Result permits Valid, Invalid, Expired {
    }

    /**
     * The token is valid. Carries the authenticated principal and optional claims.
     *
     * @param principal the authenticated identity
     * @param claims    arbitrary key-value claims extracted from the token (may be empty)
     */
    record Valid(Principal principal, Map<String, Object> claims) implements Result {

        /**
         * Convenience constructor using a simple name string as the principal.
         *
         * @param name   the principal name
         * @param claims arbitrary claims
         */
        public Valid(String name, Map<String, Object> claims) {
            this(new SimplePrincipal(name), claims);
        }

        /**
         * Convenience constructor with just a name and no claims.
         *
         * @param name the principal name
         */
        public Valid(String name) {
            this(name, Map.of());
        }
    }

    /**
     * The token is invalid (bad signature, malformed, revoked, etc.).
     *
     * @param reason human-readable reason for rejection
     */
    record Invalid(String reason) implements Result {

        /**
         * Construct with a default reason.
         */
        public Invalid() {
            this("Invalid token");
        }
    }

    /**
     * The token has expired but was otherwise valid. This signals the
     * {@link org.atmosphere.interceptor.AuthInterceptor} to attempt a refresh
     * if a {@link TokenRefresher} is configured.
     *
     * @param reason human-readable reason (e.g. "Token expired at 2026-03-13T10:00:00Z")
     */
    record Expired(String reason) implements Result {

        /**
         * Construct with a default reason.
         */
        public Expired() {
            this("Token expired");
        }
    }
}
