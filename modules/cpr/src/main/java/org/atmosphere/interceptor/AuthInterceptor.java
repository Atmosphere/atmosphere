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
package org.atmosphere.interceptor;

import org.atmosphere.auth.TokenRefresher;
import org.atmosphere.auth.TokenValidator;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authentication interceptor that validates tokens on every inbound request.
 * Tokens are extracted from the {@code X-Atmosphere-Auth} header or query param.
 * Supports optional server-side refresh via {@link TokenRefresher}.
 *
 * @since 4.0
 */
public class AuthInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AuthInterceptor.class);

    private TokenValidator validator;
    private TokenRefresher refresher;
    private String queryParamName = HeaderConfig.X_ATMOSPHERE_AUTH;
    private boolean disconnectOnFailure = true;

    private final Set<String> authenticatedResources = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredListeners = ConcurrentHashMap.newKeySet();
    private final AtomicLong totalRejected = new AtomicLong();
    private final AtomicLong totalRefreshed = new AtomicLong();

    /**
     * Create an AuthInterceptor with a programmatic validator.
     *
     * @param validator the token validator
     */
    public AuthInterceptor(TokenValidator validator) {
        this.validator = validator;
    }

    /**
     * Create an AuthInterceptor with a programmatic validator and refresher.
     *
     * @param validator the token validator
     * @param refresher the token refresher (may be null)
     */
    public AuthInterceptor(TokenValidator validator, TokenRefresher refresher) {
        this.validator = validator;
        this.refresher = refresher;
    }

    /**
     * Create an AuthInterceptor configured via init-params.
     * The {@link TokenValidator} will be loaded from the
     * {@code org.atmosphere.auth.tokenValidator} init-param.
     */
    public AuthInterceptor() {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        if (validator == null) {
            var validatorClass = config.getInitParameter(ApplicationConfig.AUTH_TOKEN_VALIDATOR);
            if (validatorClass == null || validatorClass.isBlank()) {
                logger.warn("AuthInterceptor configured without a TokenValidator. " +
                        "Set {} init-param or use the programmatic constructor.",
                        ApplicationConfig.AUTH_TOKEN_VALIDATOR);
                return;
            }
            validator = instantiate(validatorClass, TokenValidator.class);
        }

        if (refresher == null) {
            var refresherClass = config.getInitParameter(ApplicationConfig.AUTH_TOKEN_REFRESHER);
            if (refresherClass != null && !refresherClass.isBlank()) {
                refresher = instantiate(refresherClass, TokenRefresher.class);
            }
        }

        queryParamName = config.getInitParameter(
                ApplicationConfig.AUTH_TOKEN_QUERY_PARAM, HeaderConfig.X_ATMOSPHERE_AUTH);

        disconnectOnFailure = config.getInitParameter(
                ApplicationConfig.AUTH_DISCONNECT_ON_FAILURE, true);

        logger.info("Auth interceptor configured: validator={}, refresher={}, queryParam={}, disconnectOnFailure={}",
                validator != null ? validator.getClass().getSimpleName() : "NONE",
                refresher != null ? refresher.getClass().getSimpleName() : "NONE",
                queryParamName,
                disconnectOnFailure);
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        super.inspect(r);

        if (validator == null) {
            return Action.CONTINUE;
        }

        // Skip auth for post-upgrade WebSocket messages — the connection
        // was already authenticated on the initial handshake
        if (Utils.webSocketMessage(r) && authenticatedResources.contains(r.uuid())) {
            return Action.CONTINUE;
        }

        var token = extractToken(r);
        if (token == null || token.isBlank()) {
            return reject(r, "No authentication token provided");
        }

        var result = validator.validate(token);

        return switch (result) {
            case TokenValidator.Valid valid -> {
                r.getRequest().setAttribute(FrameworkConfig.AUTH_PRINCIPAL, valid.principal());
                r.getRequest().setAttribute(FrameworkConfig.AUTH_CLAIMS, valid.claims());
                authenticatedResources.add(r.uuid());
                logger.debug("Authenticated client {} as {}", r.uuid(), valid.principal().getName());
                yield Action.CONTINUE;
            }
            case TokenValidator.Expired expired -> handleExpired(r, token, expired);
            case TokenValidator.Invalid invalid -> reject(r, invalid.reason());
        };
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        var uuid = r.uuid();
        if (authenticatedResources.contains(uuid) && registeredListeners.add(uuid)) {
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                    cleanup(uuid);
                }

                @Override
                public void onClose(AtmosphereResourceEvent event) {
                    cleanup(uuid);
                }
            });
        }
    }

    @Override
    public void destroy() {
        authenticatedResources.clear();
        registeredListeners.clear();
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.FIRST_BEFORE_DEFAULT;
    }

    /**
     * Extract the auth token from the request. Checks the HTTP header first,
     * then falls back to the query parameter.
     */
    private String extractToken(AtmosphereResource r) {
        var request = r.getRequest();

        // Try HTTP header first (works for long-polling / streaming fetch requests)
        var token = request.getHeader(HeaderConfig.X_ATMOSPHERE_AUTH);
        if (token != null && !token.isBlank()) {
            return token;
        }

        // Fall back to query parameter (works for all transports including WebSocket and SSE)
        return request.getParameter(queryParamName);
    }

    /**
     * Handle an expired token — attempt refresh if a refresher is available.
     */
    private Action handleExpired(AtmosphereResource r, String expiredToken, TokenValidator.Expired expired) {
        if (refresher != null) {
            var newToken = refresher.refresh(expiredToken);
            if (newToken.isPresent()) {
                // Re-validate the refreshed token
                var revalidated = validator.validate(newToken.get());
                if (revalidated instanceof TokenValidator.Valid valid) {
                    r.getRequest().setAttribute(FrameworkConfig.AUTH_PRINCIPAL, valid.principal());
                    r.getRequest().setAttribute(FrameworkConfig.AUTH_CLAIMS, valid.claims());
                    authenticatedResources.add(r.uuid());

                    // Send the new token to the client
                    r.getResponse().setHeader(HeaderConfig.X_ATMOSPHERE_AUTH_REFRESH, newToken.get());
                    totalRefreshed.incrementAndGet();
                    logger.debug("Refreshed token for client {}", r.uuid());
                    return Action.CONTINUE;
                }
            }
        }

        // Refresh failed or not available — notify the client
        r.getResponse().setHeader(HeaderConfig.X_ATMOSPHERE_AUTH_EXPIRED, expired.reason());
        return reject(r, expired.reason());
    }

    /**
     * Reject an unauthenticated or unauthorized request.
     */
    private Action reject(AtmosphereResource r, String reason) {
        totalRejected.incrementAndGet();
        logger.debug("Auth rejected for client {}: {}", r.uuid(), reason);

        if (disconnectOnFailure) {
            try {
                r.getResponse().setStatus(401);
                r.getResponse().setHeader(HeaderConfig.X_ATMOSPHERE_ERROR, reason);
                r.close();
            } catch (Exception e) {
                logger.debug("Error closing unauthenticated resource {}", r.uuid(), e);
            }
            return Action.CANCELLED;
        }
        return Action.SKIP_ATMOSPHEREHANDLER;
    }

    private void cleanup(String uuid) {
        authenticatedResources.remove(uuid);
        registeredListeners.remove(uuid);
    }

    @SuppressWarnings("unchecked")
    private <T> T instantiate(String className, Class<T> type) {
        try {
            var clazz = (Class<? extends T>) Class.forName(className);
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot instantiate " + type.getSimpleName() + ": " + className, e);
        }
    }

    /**
     * @return total requests rejected due to authentication failure
     */
    public long totalRejected() {
        return totalRejected.get();
    }

    /**
     * @return total tokens successfully refreshed
     */
    public long totalRefreshed() {
        return totalRefreshed.get();
    }

    /**
     * @return number of currently authenticated resources
     */
    public int authenticatedCount() {
        return authenticatedResources.size();
    }

    @Override
    public String toString() {
        return "AuthInterceptor{validator=" + (validator != null ? validator.getClass().getSimpleName() : "NONE")
                + ", refresher=" + (refresher != null ? refresher.getClass().getSimpleName() : "NONE")
                + ", disconnectOnFailure=" + disconnectOnFailure + "}";
    }
}
