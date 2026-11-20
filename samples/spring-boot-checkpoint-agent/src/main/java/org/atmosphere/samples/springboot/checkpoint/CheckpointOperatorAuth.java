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
package org.atmosphere.samples.springboot.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates the reviewer behind the {@link CheckpointController} mutators
 * ({@code approve}, {@code fork}, {@code delete}). Correctness Invariant #6:
 * every mutating surface requires an authenticated principal, default deny —
 * with no token configured every mutator answers 401.
 *
 * <p>Tokens come from {@code atmosphere.checkpoint.operator-tokens}, a comma
 * separated {@code token=principal} list. The sample ships the single DEMO
 * token {@value #DEMO_TOKEN}, which signs in as {@code alice}, so the README
 * flow works out of the box; a WARN at startup says so. The caller presents the
 * token as {@code X-Atmosphere-Auth: <token>} (the header the Atmosphere
 * Console and CLI use) or {@code Authorization: Bearer <token>}. The principal
 * the token resolves to is what an approval records as {@code approvedBy}; a
 * caller can no longer assert it. A deliberately trivial stand-in for real
 * reviewer authentication (OIDC, JWT, mTLS).</p>
 */
@Component
public class CheckpointOperatorAuth {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointOperatorAuth.class);

    /** The demo token {@code application.yml} configures; never use it in production. */
    public static final String DEMO_TOKEN = "demo-alice";
    /** Header carrying the reviewer token. */
    public static final String TOKEN_HEADER = "X-Atmosphere-Auth";

    private static final String BEARER = "Bearer ";
    /** Bounded by configuration, not by traffic. */
    private final Map<String, String> principalsByToken;

    public CheckpointOperatorAuth(
            @Value("${atmosphere.checkpoint.operator-tokens:}") String tokens) {
        this.principalsByToken = parse(tokens);
        if (principalsByToken.isEmpty()) {
            logger.warn("No atmosphere.checkpoint.operator-tokens configured: checkpoint "
                    + "approve/fork/delete are denied to every caller (401)");
        } else if (principalsByToken.containsKey(DEMO_TOKEN)) {
            logger.warn("Checkpoint mutators accept the DEMO token '{}' (principal '{}'). "
                    + "Demo only: set atmosphere.checkpoint.operator-tokens (or "
                    + "CHECKPOINT_OPERATOR_TOKENS) to real secrets before exposing this app",
                    DEMO_TOKEN, principalsByToken.get(DEMO_TOKEN));
        }
    }

    /**
     * The principal a request authenticates as.
     *
     * @param tokenHeader   the {@value #TOKEN_HEADER} header, may be {@code null}
     * @param authorization the {@code Authorization} header, may be {@code null}
     * @return the principal, or empty when the request carries no valid token
     */
    public Optional<String> authenticate(String tokenHeader, String authorization) {
        var token = tokenHeader;
        if ((token == null || token.isBlank()) && authorization != null
                && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            token = authorization.substring(BEARER.length());
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var presented = token.trim().getBytes(StandardCharsets.UTF_8);
        String principal = null;
        // Compare against every configured token in constant time per token,
        // so the response time does not reveal how much of a token matched.
        for (var entry : principalsByToken.entrySet()) {
            if (MessageDigest.isEqual(presented, entry.getKey().getBytes(StandardCharsets.UTF_8))) {
                principal = entry.getValue();
            }
        }
        return Optional.ofNullable(principal);
    }

    static Map<String, String> parse(String tokens) {
        var parsed = new LinkedHashMap<String, String>();
        if (tokens == null) {
            return Map.of();
        }
        for (var pair : tokens.split(",")) {
            var eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                if (!pair.isBlank()) {
                    logger.warn("Ignoring malformed atmosphere.checkpoint.operator-tokens entry "
                            + "(expected token=principal)");
                }
                continue;
            }
            var token = pair.substring(0, eq).trim();
            var principal = pair.substring(eq + 1).trim();
            if (!token.isEmpty() && !principal.isEmpty()) {
                parsed.put(token, principal);
            }
        }
        return Map.copyOf(parsed);
    }
}
