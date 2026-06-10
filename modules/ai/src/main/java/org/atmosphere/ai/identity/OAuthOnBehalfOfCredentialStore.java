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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete OAuth 2.0 Token Exchange ("on-behalf-of", RFC 8693)
 * {@link CredentialStore}. The directory-principal half of per-agent identity
 * ({@link AgentIdentity}, {@link PermissionMode}) already ships; this is the
 * delegated-credential half — it exchanges a user's stored <em>subject token</em>
 * for a short-lived <em>access token</em> scoped to a downstream tool/resource,
 * so an agent calls external APIs <strong>as the user</strong> rather than with
 * a shared service credential.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>The application stores each user's delegated subject token via
 *       {@link #put}{@code (userId, config.subjectTokenKey(), token)} (delegated
 *       to the backing {@link CredentialStore}, which may be the encrypted one).</li>
 *   <li>A tool resolves a downstream credential with {@link #get}{@code (userId,
 *       scope)}. The store performs an RFC 8693 exchange at the configured token
 *       endpoint and returns the resulting access token.</li>
 *   <li>Exchanged tokens are cached per {@code (userId, scope)} until shortly
 *       before expiry, bounded by {@link OAuthOboConfig#maxCachedTokens()}.</li>
 * </ol>
 *
 * <h2>Security posture (fail-closed)</h2>
 * No subject token for the user, an exchange HTTP error, or an unparseable
 * response all yield {@link Optional#empty()} — the tool then runs without a
 * credential and the permission gate denies it, rather than the agent falling
 * back to a broader credential. Tokens are never logged (only
 * {@link CredentialStore#identifier} HMAC ids).
 */
public final class OAuthOnBehalfOfCredentialStore implements CredentialStore {

    private static final Logger logger =
            LoggerFactory.getLogger(OAuthOnBehalfOfCredentialStore.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final CredentialStore backing;
    private final OAuthOboConfig config;
    private final HttpClient client;
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public OAuthOnBehalfOfCredentialStore(CredentialStore backing, OAuthOboConfig config) {
        this(backing, config, HttpClient.newHttpClient());
    }

    public OAuthOnBehalfOfCredentialStore(CredentialStore backing, OAuthOboConfig config,
                                          HttpClient client) {
        if (backing == null || config == null) {
            throw new IllegalArgumentException("backing store and config must not be null");
        }
        this.backing = backing;
        this.config = config;
        this.client = client != null ? client : HttpClient.newHttpClient();
    }

    /**
     * Return an access token for {@code userId} scoped to {@code scope},
     * exchanging the user's stored subject token on-behalf-of. {@code scope} is
     * the downstream {@code scope} requested; pass {@code null}/blank to use the
     * config's default scope.
     */
    @Override
    public Optional<String> get(String userId, String scope) {
        var requestedScope = scope == null || scope.isBlank() ? config.defaultScope() : scope;
        var cacheKey = userId + "::" + (requestedScope == null ? "" : requestedScope);

        var cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh(config.clockSkew())) {
            return Optional.of(cached.accessToken());
        }

        var subject = backing.get(userId, config.subjectTokenKey());
        if (subject.isEmpty()) {
            logger.debug("No subject token for user {} under key {} — cannot exchange",
                    userId, config.subjectTokenKey());
            return Optional.empty();
        }

        var exchanged = exchange(subject.get(), requestedScope);
        exchanged.ifPresent(token -> cacheToken(cacheKey, token));
        return exchanged.map(CachedToken::accessToken);
    }

    /**
     * Store a secret in the backing store. To enable on-behalf-of exchange for a
     * user, store their subject token under {@link OAuthOboConfig#subjectTokenKey()}.
     */
    @Override
    public void put(String userId, String key, String secret) {
        backing.put(userId, key, secret);
        if (config.subjectTokenKey().equals(key)) {
            invalidate(userId);
        }
    }

    @Override
    public void delete(String userId, String key) {
        backing.delete(userId, key);
        if (config.subjectTokenKey().equals(key)) {
            invalidate(userId);
        }
    }

    /**
     * Identifier of the user's <em>subject</em> credential — derived without
     * triggering an exchange, so admin surfaces can attribute usage cheaply.
     */
    @Override
    public Optional<String> identifier(String userId, String key) {
        return backing.identifier(userId, config.subjectTokenKey());
    }

    @Override
    public String name() {
        return "oauth-obo";
    }

    private Optional<CachedToken> exchange(String subjectToken, String scope) {
        var form = new StringBuilder()
                .append("grant_type=").append(enc(OAuthOboConfig.GRANT_TYPE))
                .append("&subject_token=").append(enc(subjectToken))
                .append("&subject_token_type=").append(enc(config.subjectTokenType()));
        if (config.requestedTokenType() != null) {
            form.append("&requested_token_type=").append(enc(config.requestedTokenType()));
        }
        if (scope != null && !scope.isBlank()) {
            form.append("&scope=").append(enc(scope));
        }
        if (config.defaultAudience() != null && !config.defaultAudience().isBlank()) {
            form.append("&audience=").append(enc(config.defaultAudience()));
        }

        var basic = Base64.getEncoder().encodeToString(
                (config.clientId() + ":" + (config.clientSecret() == null ? "" : config.clientSecret()))
                        .getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(config.tokenEndpoint())
                .timeout(config.httpTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();

        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.warn("OBO token exchange failed: HTTP {} from {}",
                        response.statusCode(), config.tokenEndpoint());
                return Optional.empty();
            }
            return parse(response.body());
        } catch (IOException e) {
            logger.warn("OBO token exchange I/O error against {}: {}",
                    config.tokenEndpoint(), e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<CachedToken> parse(String body) {
        try {
            var node = JSON.readTree(body);
            var accessNode = node.get("access_token");
            if (accessNode == null || !accessNode.isString()) {
                logger.warn("OBO exchange response had no access_token");
                return Optional.empty();
            }
            var expiresIn = node.has("expires_in") && node.get("expires_in").isNumber()
                    ? node.get("expires_in").asLong() : 300L;
            return Optional.of(new CachedToken(accessNode.stringValue(),
                    Instant.now().plusSeconds(expiresIn)));
        } catch (RuntimeException e) {
            logger.warn("OBO exchange response did not parse as JSON");
            return Optional.empty();
        }
    }

    private void cacheToken(String cacheKey, CachedToken token) {
        cache.put(cacheKey, token);
        if (cache.size() > config.maxCachedTokens()) {
            evictOldest();
        }
    }

    private void evictOldest() {
        var excess = cache.size() - config.maxCachedTokens();
        if (excess <= 0) {
            return;
        }
        cache.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(excess)
                .map(java.util.Map.Entry::getKey)
                .forEach(cache::remove);
    }

    private void invalidate(String userId) {
        cache.keySet().removeIf(k -> k.startsWith(userId + "::"));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean isFresh(Duration skew) {
            return Instant.now().isBefore(expiresAt.minus(skew));
        }
    }
}
