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
package org.atmosphere.mcp.runtime;

import org.atmosphere.auth.TokenValidator;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP server's OAuth 2.1 <em>resource server</em> support (MCP
 * authorization spec; RFC 9728 / RFC 6750): the Protected Resource Metadata
 * document, the {@code WWW-Authenticate} challenge, and bearer-token
 * authentication of requests.
 *
 * <p>Two validation paths are honored (Correctness Invariant #6, default-deny):</p>
 * <ol>
 *   <li><b>Framework-set principal</b> — a servlet resource-server filter
 *       (Spring Security {@code oauth2ResourceServer}) or the framework-native
 *       {@code AuthInterceptor} already authenticated the request, exposed via
 *       {@link AtmosphereRequest#getUserPrincipal()} or the
 *       {@link FrameworkConfig#AUTH_PRINCIPAL} attribute.</li>
 *   <li><b>Local {@link TokenValidator}</b> — when a validator is configured via
 *       the {@link ApplicationConfig#AUTH_TOKEN_VALIDATOR} init-parameter, MCP
 *       itself extracts the {@code Authorization: Bearer} token (RFC 6750) and
 *       validates it. This works uniformly on any container (Spring, Quarkus,
 *       embedded) with no per-framework wiring.</li>
 * </ol>
 *
 * <p>Opt-in and default-deny when enabled (Correctness Invariant #6): it is
 * disabled unless a {@code resource} identifier and at least one
 * {@code authorization_servers} entry are configured. When disabled the MCP
 * endpoint stays open (back-compat); when enabled, an unauthenticated request
 * gets a {@code 401} with a {@code WWW-Authenticate} pointing at the metadata.</p>
 *
 * <p>Configuration (Atmosphere init-parameters):</p>
 * <ul>
 *   <li>{@link #RESOURCE} — the canonical MCP server URI (RFC 8707 resource)</li>
 *   <li>{@link #AUTHORIZATION_SERVERS} — comma-separated issuer URLs (≥1 required)</li>
 *   <li>{@link #SCOPES} — comma-separated supported scopes (optional)</li>
 *   <li>{@link #METADATA_URL} — the Protected Resource Metadata URL advertised in
 *       {@code WWW-Authenticate}; defaults to {@code <resource>/.well-known/oauth-protected-resource}</li>
 * </ul>
 */
public final class McpAuthorization {

    public static final String RESOURCE = "org.atmosphere.mcp.auth.resource";
    public static final String AUTHORIZATION_SERVERS = "org.atmosphere.mcp.auth.authorizationServers";
    public static final String SCOPES = "org.atmosphere.mcp.auth.scopes";
    public static final String METADATA_URL = "org.atmosphere.mcp.auth.resourceMetadataUrl";

    /** Standard suffix the Protected Resource Metadata is served under (RFC 9728). */
    public static final String WELL_KNOWN_PATH = "/.well-known/oauth-protected-resource";

    private static final Logger logger = LoggerFactory.getLogger(McpAuthorization.class);

    private final String resource;
    private final List<String> authorizationServers;
    private final List<String> scopes;
    private final String metadataUrl;
    private final boolean enabled;
    private final TokenValidator validator;

    private McpAuthorization(String resource, List<String> authorizationServers,
                             List<String> scopes, String metadataUrl, TokenValidator validator) {
        this.resource = resource;
        this.authorizationServers = List.copyOf(authorizationServers);
        this.scopes = List.copyOf(scopes);
        this.metadataUrl = metadataUrl;
        this.validator = validator;
        this.enabled = resource != null && !resource.isBlank() && !this.authorizationServers.isEmpty();
    }

    /** Build from Atmosphere config init-parameters; disabled when unconfigured. */
    public static McpAuthorization from(AtmosphereConfig config) {
        if (config == null) {
            return disabled();
        }
        String resource;
        try {
            resource = trimToNull(config.getInitParameter(RESOURCE));
        } catch (RuntimeException e) {
            return disabled();
        }
        var servers = csv(config.getInitParameter(AUTHORIZATION_SERVERS));
        var scopes = csv(config.getInitParameter(SCOPES));
        var metadataUrl = trimToNull(config.getInitParameter(METADATA_URL));
        if (metadataUrl == null && resource != null) {
            metadataUrl = resource.replaceAll("/+$", "") + WELL_KNOWN_PATH;
        }
        return new McpAuthorization(resource, servers, scopes, metadataUrl, loadValidator(config));
    }

    /** An explicitly disabled instance (no authorization enforced). */
    public static McpAuthorization disabled() {
        return new McpAuthorization(null, List.of(), List.of(), null, null);
    }

    /**
     * Authenticate a request (Invariant #6, default-deny). Returns {@code true}
     * when the request is authenticated — either a framework filter/interceptor
     * already set the principal, or a configured {@link TokenValidator} accepts
     * the {@code Authorization: Bearer} token (in which case the principal +
     * claims are published as request attributes for downstream governance).
     * Returns {@code false} for an unauthenticated or invalid request, which the
     * caller answers with {@code 401} + {@link #wwwAuthenticate()}.
     */
    public boolean authenticate(AtmosphereRequest request) {
        if (request.getUserPrincipal() != null
                || request.getAttribute(FrameworkConfig.AUTH_PRINCIPAL) != null) {
            return true;
        }
        if (validator == null) {
            return false;
        }
        var bearer = bearerToken(request);
        if (bearer == null) {
            return false;
        }
        if (validator.validate(bearer) instanceof TokenValidator.Valid valid) {
            request.setAttribute(FrameworkConfig.AUTH_PRINCIPAL, valid.principal());
            request.setAttribute(FrameworkConfig.AUTH_CLAIMS, valid.claims());
            return true;
        }
        return false; // Invalid or Expired
    }

    /** Extract the RFC 6750 {@code Authorization: Bearer <token>} value, or {@code null}. */
    private static String bearerToken(AtmosphereRequest request) {
        var header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        var trimmed = header.strip();
        if (trimmed.length() > 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            var token = trimmed.substring(7).strip();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /**
     * Instantiate the configured {@link TokenValidator} by class name (no-arg
     * constructor), or {@code null} when none is configured. A configured-but-
     * unloadable validator fails closed (returns {@code null} → bearer tokens
     * can't be validated locally → requests without an externally-set principal
     * are challenged) and logs a WARN — never a silent open.
     */
    private static TokenValidator loadValidator(AtmosphereConfig config) {
        String className;
        try {
            className = trimToNull(config.getInitParameter(ApplicationConfig.AUTH_TOKEN_VALIDATOR));
        } catch (RuntimeException e) {
            logger.trace("Could not read {} init-parameter", ApplicationConfig.AUTH_TOKEN_VALIDATOR, e);
            return null;
        }
        if (className == null) {
            return null;
        }
        try {
            // Load via the thread context classloader: under Quarkus the app's
            // TokenValidator lives in a different classloader than this jar, so a
            // plain Class.forName (this class's loader) would not see it. The TCCL
            // sees both app and dependency classes; fall back to this loader.
            var tccl = Thread.currentThread().getContextClassLoader();
            var loader = tccl != null ? tccl : McpAuthorization.class.getClassLoader();
            return Class.forName(className, true, loader)
                    .asSubclass(TokenValidator.class)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warn("MCP authorization: configured TokenValidator '{}' could not be instantiated "
                    + "— bearer tokens will not be validated locally (fail-closed)", className, e);
            return null;
        }
    }

    /** Whether authorization is configured and should be enforced. */
    public boolean enabled() {
        return enabled;
    }

    /** The URL the Protected Resource Metadata is advertised at, or {@code null} when disabled. */
    public String metadataUrl() {
        return metadataUrl;
    }

    /**
     * The RFC 9728 Protected Resource Metadata document. The MCP spec requires
     * a non-empty {@code authorization_servers}; clients use it to discover the
     * authorization server to obtain a token from.
     */
    public Map<String, Object> protectedResourceMetadata() {
        var m = new LinkedHashMap<String, Object>();
        m.put("resource", resource);
        m.put("authorization_servers", authorizationServers);
        m.put("bearer_methods_supported", List.of("header"));
        if (!scopes.isEmpty()) {
            m.put("scopes_supported", scopes);
        }
        return m;
    }

    /**
     * The {@code WWW-Authenticate} challenge for a {@code 401} (RFC 9728 §5.1):
     * {@code Bearer resource_metadata="<url>"}, plus a {@code scope} hint when
     * scopes are configured (RFC 6750 §3). The values are operator-configured,
     * not client-derived, so no untrusted input is interpolated.
     */
    public String wwwAuthenticate() {
        var sb = new StringBuilder("Bearer resource_metadata=\"").append(metadataUrl).append('"');
        if (!scopes.isEmpty()) {
            sb.append(", scope=\"").append(String.join(" ", scopes)).append('"');
        }
        return sb.toString();
    }

    private static List<String> csv(String raw) {
        var out = new ArrayList<String>();
        if (raw != null) {
            for (var part : raw.split(",")) {
                var t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        var t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
