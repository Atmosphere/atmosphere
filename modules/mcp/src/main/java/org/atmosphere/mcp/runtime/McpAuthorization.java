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

import org.atmosphere.cpr.AtmosphereConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP server's OAuth 2.1 <em>resource server</em> metadata (MCP
 * authorization spec; RFC 9728 / RFC 6750). {@code modules/mcp} owns only the
 * thin protocol glue — the Protected Resource Metadata document and the
 * {@code WWW-Authenticate} challenge — while the host framework (Spring
 * Security resource-server, {@code quarkus-oidc}) performs the actual token
 * validation and populates the request principal.
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

    private final String resource;
    private final List<String> authorizationServers;
    private final List<String> scopes;
    private final String metadataUrl;
    private final boolean enabled;

    private McpAuthorization(String resource, List<String> authorizationServers,
                             List<String> scopes, String metadataUrl) {
        this.resource = resource;
        this.authorizationServers = List.copyOf(authorizationServers);
        this.scopes = List.copyOf(scopes);
        this.metadataUrl = metadataUrl;
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
        return new McpAuthorization(resource, servers, scopes, metadataUrl);
    }

    /** An explicitly disabled instance (no authorization enforced). */
    public static McpAuthorization disabled() {
        return new McpAuthorization(null, List.of(), List.of(), null);
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
