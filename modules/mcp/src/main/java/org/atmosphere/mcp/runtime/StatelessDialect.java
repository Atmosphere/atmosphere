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

import org.atmosphere.mcp.protocol.JsonRpc;
import org.atmosphere.mcp.protocol.Mcp2026;
import org.atmosphere.mcp.protocol.McpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The stateless dialect for MCP {@code 2026-07-28} (SEP-2567 remove sessions,
 * SEP-2575 remove the {@code initialize} handshake).
 *
 * <p>There is no session and no handshake: the client repeats its protocol
 * version, identity, and capabilities in {@code params._meta} on every request,
 * and learns server capabilities on demand via {@code server/discover}. Because
 * this dialect never creates an {@link McpSession}, the transport never emits an
 * {@code Mcp-Session-Id}, so any instance behind a round-robin load balancer can
 * serve any request.</p>
 *
 * <p>Tool/resource/prompt execution is identical to the session model — it
 * delegates straight back to {@link McpProtocolHandler}'s shared handlers — so
 * the two dialects can never diverge on what a tool returns (mode parity). The
 * stateless-specific shaping is the envelope: the mandatory {@code resultType}
 * field, the {@code CacheableResult} fields ({@code ttlMs}/{@code cacheScope},
 * SEP-2549) on list/read/discover results, W3C trace-context propagation from
 * {@code _meta} (SEP-414), and the {@code server/discover} reply.</p>
 *
 * <p>Session-only methods ({@code resources/subscribe}, {@code tasks/*}) report
 * {@code METHOD_NOT_FOUND} here; their stateless equivalents (the restructured
 * Tasks extension) land in a later slice and are not advertised until they
 * exist.</p>
 */
final class StatelessDialect implements ProtocolDialect {

    private static final Logger logger = LoggerFactory.getLogger(StatelessDialect.class);

    private final McpProtocolHandler core;

    StatelessDialect(McpProtocolHandler core) {
        this.core = core;
    }

    // The trace scope's only purpose is to be closed after dispatch (restoring
    // the prior OTel context); -Xlint:try flags the unreferenced resource, same
    // as McpTracing.traced(). Suppression is intentional and local.
    @Override
    @SuppressWarnings("try")
    public JsonRpc.Response dispatch(McpRequestContext ctx) {
        // SEP-414: if the request carries W3C trace context in _meta and tracing
        // is active, make it the current OTel context so any span created during
        // dispatch parents off the caller's distributed trace. The TraceScope is
        // a plain AutoCloseable (no OpenTelemetry type leaks here — OTel is an
        // optional dependency confined to McpTracing).
        var tracing = core.tracing();
        if (tracing == null) {
            return dispatch0(ctx);
        }
        try (var ignored = tracing.withRemoteContext(ctx.traceContext())) {
            return dispatch0(ctx);
        }
    }

    private JsonRpc.Response dispatch0(McpRequestContext ctx) {
        var method = ctx.method();

        // server/discover is the one method that must answer regardless of the
        // version the client guessed — it exists precisely so the client can
        // learn what we support. Every other method is gated on a version match.
        if (McpMethod.SERVER_DISCOVER.equals(method)) {
            return handleDiscover(ctx.id());
        }

        var requested = ctx.protocolVersion();
        if (!Mcp2026.VERSION.equals(requested)) {
            return unsupportedVersion(ctx.id(), requested);
        }

        logger.debug("Stateless MCP request '{}' from {} (v{})",
                method, ctx.clientName(), requested);

        // tools/list, resources/list, resources/read, prompts/list are
        // CacheableResult per schema → carry ttlMs + cacheScope (SEP-2549).
        // tools/call, prompts/get and ping are not cacheable → resultType only.
        return switch (method) {
            case McpMethod.PING -> complete(JsonRpc.Response.success(ctx.id(), Map.of()));
            case McpMethod.TOOLS_LIST -> cacheable(core.handleToolsList(ctx.id()));
            case McpMethod.TOOLS_CALL ->
                    complete(core.handleToolsCall(ctx.resource(), ctx.id(), ctx.params()));
            case McpMethod.RESOURCES_LIST -> cacheable(core.handleResourcesList(ctx.id()));
            case McpMethod.RESOURCES_READ -> cacheable(core.handleResourcesRead(ctx.id(), ctx.params()));
            case McpMethod.PROMPTS_LIST -> cacheable(core.handlePromptsList(ctx.id()));
            case McpMethod.PROMPTS_GET -> complete(core.handlePromptsGet(ctx.id(), ctx.params()));
            default -> JsonRpc.Response.error(ctx.id(), JsonRpc.METHOD_NOT_FOUND,
                    "Method '" + method + "' is not available in the "
                            + Mcp2026.VERSION + " stateless dialect");
        };
    }

    /**
     * {@code server/discover} (SEP-2575): advertise the versions and runtime
     * capabilities the server actually serves. {@code supportedVersions} lists
     * the stateless revision first, then the handshake revisions that remain
     * reachable via {@code initialize}.
     */
    private JsonRpc.Response handleDiscover(Object id) {
        var supported = new ArrayList<String>();
        supported.add(Mcp2026.VERSION);
        for (var v : McpProtocolHandler.SUPPORTED_VERSIONS) {
            if (!supported.contains(v)) {
                supported.add(v);
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("supportedVersions", supported);
        result.put("capabilities", statelessCapabilities());
        result.put("serverInfo", serverInfo());
        // DiscoverResult extends CacheableResult → cacheable() stamps the
        // required ttlMs + cacheScope alongside resultType. The default ttlMs is
        // 0 (always revalidate), honest about capabilities being mutable via
        // runtime registry changes; a deployment with a static catalog can raise
        // it via the cacheTtlMs init-param.
        return cacheable(JsonRpc.Response.success(id, result));
    }

    /**
     * Capabilities the stateless model actually serves today: tools, resources
     * (read-only — stateless drops session subscriptions), and prompts, each
     * advertised only when the registry holds at least one entry. Tasks and
     * subscriptions are deliberately absent until their stateless equivalents
     * ship, so discovery never advertises a method this dialect would then
     * reject (Runtime Truth).
     */
    private Map<String, Object> statelessCapabilities() {
        var caps = new LinkedHashMap<String, Object>();
        if (!core.registry().tools().isEmpty()) {
            caps.put("tools", Map.of());
        }
        if (!core.registry().resources().isEmpty()) {
            caps.put("resources", Map.of());
        }
        if (!core.registry().prompts().isEmpty()) {
            caps.put("prompts", Map.of());
        }
        return caps;
    }

    private Map<String, Object> serverInfo() {
        var info = new LinkedHashMap<String, Object>();
        info.put("name", core.serverName());
        info.put("version", core.serverVersion());
        if (!core.guardrails().isEmpty()) {
            info.put("guardrails", core.guardrails());
        }
        return info;
    }

    /**
     * The {@code -32004 UnsupportedProtocolVersionError}: the client pinned a
     * version this dialect does not serve. {@code data} carries the
     * {@code supported} list and the {@code requested} value so the client can
     * pick a mutually supported version and retry.
     */
    private JsonRpc.Response unsupportedVersion(Object id, String requested) {
        var data = new LinkedHashMap<String, Object>();
        data.put("supported", List.of(Mcp2026.VERSION));
        data.put("requested", requested);
        return JsonRpc.Response.error(id, Mcp2026.UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported MCP protocol version: " + requested, data);
    }

    /**
     * Stamp the mandatory {@code resultType: "complete"} discriminator onto a
     * non-cacheable result (servers on this revision MUST include it).
     */
    private static JsonRpc.Response complete(JsonRpc.Response response) {
        return stamp(response, null);
    }

    /**
     * Stamp {@code resultType} plus the {@code CacheableResult} fields
     * ({@code ttlMs}/{@code cacheScope}, SEP-2549) onto a list/read/discover
     * result. {@code cacheScope} is {@code "public"}: tool/resource/prompt
     * catalogs and reads are not principal-specific in this server.
     */
    private JsonRpc.Response cacheable(JsonRpc.Response response) {
        var ttl = core.cacheTtlMs();
        return stamp(response, fields -> {
            fields.put(Mcp2026.CACHE_TTL_MS, ttl);
            fields.put(Mcp2026.CACHE_SCOPE, Mcp2026.CACHE_SCOPE_PUBLIC);
        });
    }

    /**
     * Rebuild a successful result map with {@code resultType} first, then any
     * {@code extra} envelope fields, then the original payload. Errors and
     * non-map results pass through untouched.
     */
    private static JsonRpc.Response stamp(JsonRpc.Response response,
                                          Consumer<Map<String, Object>> extra) {
        if (response.error() != null || !(response.result() instanceof Map<?, ?> existing)) {
            return response;
        }
        var out = new LinkedHashMap<String, Object>();
        out.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_COMPLETE);
        if (extra != null) {
            extra.accept(out);
        }
        existing.forEach((k, v) -> out.put(String.valueOf(k), v));
        return JsonRpc.Response.success(response.id(), out);
    }
}
