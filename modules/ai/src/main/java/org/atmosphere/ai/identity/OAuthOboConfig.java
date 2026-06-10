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
package org.atmosphere.ai.identity;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration for {@link OAuthOnBehalfOfCredentialStore} — an RFC 8693 OAuth
 * 2.0 Token Exchange ("on-behalf-of") credential vault.
 *
 * @param tokenEndpoint      the authorization server's token endpoint
 * @param clientId           the agent's OAuth client id (HTTP Basic auth)
 * @param clientSecret       the agent's OAuth client secret
 * @param subjectTokenKey    the {@link CredentialStore} key under which each
 *                           user's delegated subject token is stored in the
 *                           backing store
 * @param subjectTokenType   {@code subject_token_type} sent in the exchange
 *                           (default: access token)
 * @param requestedTokenType {@code requested_token_type}, or {@code null} to let
 *                           the server choose
 * @param defaultScope       {@code scope} requested when the lookup key does not
 *                           specify one; {@code null} omits it
 * @param defaultAudience    {@code audience} requested for the downstream
 *                           resource; {@code null} omits it
 * @param httpTimeout        per-exchange HTTP timeout
 * @param clockSkew          refresh a cached token this long before it expires
 * @param maxCachedTokens    bound on the exchanged-token cache (Backpressure)
 */
public record OAuthOboConfig(
        URI tokenEndpoint,
        String clientId,
        String clientSecret,
        String subjectTokenKey,
        String subjectTokenType,
        String requestedTokenType,
        String defaultScope,
        String defaultAudience,
        Duration httpTimeout,
        Duration clockSkew,
        int maxCachedTokens) {

    /** RFC 8693 token-exchange grant type. */
    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    /** RFC 8693 access-token type URN. */
    public static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    public OAuthOboConfig {
        if (tokenEndpoint == null) {
            throw new IllegalArgumentException("tokenEndpoint must not be null");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        subjectTokenKey = subjectTokenKey != null && !subjectTokenKey.isBlank()
                ? subjectTokenKey : "oauth.subject_token";
        subjectTokenType = subjectTokenType != null && !subjectTokenType.isBlank()
                ? subjectTokenType : ACCESS_TOKEN_TYPE;
        httpTimeout = httpTimeout != null ? httpTimeout : Duration.ofSeconds(10);
        clockSkew = clockSkew != null ? clockSkew : Duration.ofSeconds(30);
        if (maxCachedTokens <= 0) {
            maxCachedTokens = 1000;
        }
    }

    public static Builder builder(URI tokenEndpoint, String clientId, String clientSecret) {
        return new Builder(tokenEndpoint, clientId, clientSecret);
    }

    public static final class Builder {
        private final URI tokenEndpoint;
        private final String clientId;
        private final String clientSecret;
        private String subjectTokenKey;
        private String subjectTokenType;
        private String requestedTokenType;
        private String defaultScope;
        private String defaultAudience;
        private Duration httpTimeout;
        private Duration clockSkew;
        private int maxCachedTokens;

        private Builder(URI tokenEndpoint, String clientId, String clientSecret) {
            this.tokenEndpoint = tokenEndpoint;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public Builder subjectTokenKey(String key) {
            this.subjectTokenKey = key;
            return this;
        }

        public Builder subjectTokenType(String type) {
            this.subjectTokenType = type;
            return this;
        }

        public Builder requestedTokenType(String type) {
            this.requestedTokenType = type;
            return this;
        }

        public Builder defaultScope(String scope) {
            this.defaultScope = scope;
            return this;
        }

        public Builder defaultAudience(String audience) {
            this.defaultAudience = audience;
            return this;
        }

        public Builder httpTimeout(Duration timeout) {
            this.httpTimeout = timeout;
            return this;
        }

        public Builder clockSkew(Duration skew) {
            this.clockSkew = skew;
            return this;
        }

        public Builder maxCachedTokens(int max) {
            this.maxCachedTokens = max;
            return this;
        }

        public OAuthOboConfig build() {
            return new OAuthOboConfig(tokenEndpoint, clientId, clientSecret, subjectTokenKey,
                    subjectTokenType, requestedTokenType, defaultScope, defaultAudience,
                    httpTimeout, clockSkew, maxCachedTokens);
        }
    }
}
