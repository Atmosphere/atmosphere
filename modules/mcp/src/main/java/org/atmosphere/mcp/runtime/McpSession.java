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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-connection MCP session state (initialization, client capabilities).
 */
public final class McpSession {

    /** Attribute key used to store the session on AtmosphereResource. */
    public static final String ATTRIBUTE_KEY = "org.atmosphere.mcp.session";

    private volatile boolean initialized;
    private volatile Map<String, Object> clientCapabilities = Map.of();
    private volatile String clientName;
    private volatile String clientVersion;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public boolean isInitialized() {
        return initialized;
    }

    public void markInitialized() {
        this.initialized = true;
    }

    public Map<String, Object> clientCapabilities() {
        return clientCapabilities;
    }

    public void setClientInfo(String name, String version, Map<String, Object> capabilities) {
        this.clientName = name;
        this.clientVersion = version;
        this.clientCapabilities = capabilities != null ? capabilities : Map.of();
    }

    public String clientName() {
        return clientName;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
