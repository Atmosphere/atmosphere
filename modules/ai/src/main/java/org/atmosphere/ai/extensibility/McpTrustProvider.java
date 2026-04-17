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
package org.atmosphere.ai.extensibility;

import org.atmosphere.ai.identity.CredentialStore;

import java.util.Optional;

/**
 * Pluggable per-user trust model for MCP personal server credentials. The
 * user brings their own MCP tools (GitHub, Gmail, filesystem, etc.) and
 * the trust provider decides how Atmosphere obtains the credentials it
 * needs to call those tools on the user's behalf.
 *
 * <h2>Three built-in strategies</h2>
 *
 * <ul>
 *   <li>{@link CredentialStoreBacked} — reads from an
 *       {@link CredentialStore} (typically
 *       {@code AtmosphereEncryptedCredentialStore}). The Atmosphere-house
 *       default for local development and straightforward production.</li>
 *   <li>{@code OsKeychain} and {@code OAuthDelegated} — declared intent in
 *       v0.5. Ship as separate modules that implement this SPI when needed;
 *       they are intentionally not in-tree here to keep the foundation
 *       dependency-free.</li>
 * </ul>
 */
public interface McpTrustProvider {

    /**
     * Resolve the credential for a user's configured MCP server. Returns
     * {@link Optional#empty()} if the user has no credential configured
     * for that server — callers must treat this as "user has not yet
     * authorized this server" and fail the tool call fast rather than
     * attempting to call the MCP server without credentials.
     *
     * @param userId        the user the agent is acting on behalf of
     * @param mcpServerId   identifier of the MCP server in the user's
     *                      {@code MCP.md} configuration
     */
    Optional<String> resolve(String userId, String mcpServerId);

    /** Stable short identifier of this provider. */
    String name();

    /** Credential-store-backed provider. The Atmosphere-house default. */
    final class CredentialStoreBacked implements McpTrustProvider {

        private final CredentialStore store;
        private final String keyPrefix;

        /**
         * Construct with a specific store and an optional key prefix. The
         * lookup key inside the store is {@code prefix + mcpServerId}.
         * {@code prefix} may be empty for flat key spaces.
         */
        public CredentialStoreBacked(CredentialStore store, String keyPrefix) {
            this.store = java.util.Objects.requireNonNull(store, "store");
            this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        }

        public CredentialStoreBacked(CredentialStore store) {
            this(store, "mcp:");
        }

        @Override
        public Optional<String> resolve(String userId, String mcpServerId) {
            return store.get(userId, keyPrefix + mcpServerId);
        }

        @Override
        public String name() {
            return "credential-store-backed";
        }
    }
}
