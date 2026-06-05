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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.protocol.Mcp2026;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single parsed JSON-RPC request handed to a {@link ProtocolDialect},
 * decoupled from any session assumption.
 *
 * <p>{@code meta} is {@code params._meta} (may be {@code null} for legacy
 * requests). On the stateless model it carries the client's per-request
 * protocol version, identity, and capabilities (see {@link Mcp2026}); the
 * accessors below read those keys defensively.</p>
 *
 * @param resource the underlying transport resource (request/response, principal)
 * @param id       the JSON-RPC request id (already coerced to Number/String)
 * @param method   the JSON-RPC method
 * @param params   the {@code params} node, or {@code null}
 * @param meta     the {@code params._meta} node, or {@code null}
 */
record McpRequestContext(AtmosphereResource resource, Object id, String method,
                         JsonNode params, JsonNode meta) {

    /**
     * Build a context from a request node. Extracts {@code params} and
     * {@code params._meta} once so dialects don't re-walk the tree.
     */
    static McpRequestContext from(AtmosphereResource resource, Object id,
                                  String method, JsonNode params) {
        var meta = params != null ? params.get("_meta") : null;
        return new McpRequestContext(resource, id, method, params, meta);
    }

    /**
     * The protocol version the client pinned in {@code _meta}, or {@code null}
     * if absent. A non-null value is the signal that the client speaks the
     * stateless {@code 2026-07-28} model rather than the handshake model.
     */
    String protocolVersion() {
        return metaString(Mcp2026.META_PROTOCOL_VERSION);
    }

    /** Client software name from {@code _meta.clientInfo}, or {@code "unknown"}. */
    String clientName() {
        if (meta == null) {
            return "unknown";
        }
        var info = meta.get(Mcp2026.META_CLIENT_INFO);
        if (info != null && info.has("name") && info.get("name").isString()) {
            return info.get("name").stringValue();
        }
        return "unknown";
    }

    /**
     * The W3C Trace Context carried in {@code _meta} (SEP-414) as a propagation
     * carrier: the bare keys {@code traceparent}/{@code tracestate}/{@code baggage}
     * that are present. Empty when the request carries no trace context.
     */
    Map<String, String> traceContext() {
        if (meta == null) {
            return Map.of();
        }
        var carrier = new LinkedHashMap<String, String>();
        putIfString(carrier, Mcp2026.META_TRACEPARENT);
        putIfString(carrier, Mcp2026.META_TRACESTATE);
        putIfString(carrier, Mcp2026.META_BAGGAGE);
        return carrier;
    }

    private void putIfString(Map<String, String> carrier, String key) {
        var v = meta.get(key);
        if (v != null && v.isString()) {
            carrier.put(key, v.stringValue());
        }
    }

    private String metaString(String key) {
        if (meta == null) {
            return null;
        }
        var v = meta.get(key);
        return v != null && v.isString() ? v.stringValue() : null;
    }
}
