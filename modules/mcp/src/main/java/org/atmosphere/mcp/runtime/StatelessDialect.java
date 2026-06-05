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
 * only stateless-specific shaping is the mandatory {@code resultType} envelope
 * field and the {@code server/discover} reply.</p>
 *
 * <p>Session-only methods ({@code resources/subscribe}, {@code tasks/*}) report
 * {@code METHOD_NOT_FOUND} here; their stateless equivalents (cache-TTL change
 * detection, the restructured Tasks extension) land in later slices and are not
 * advertised until they exist.</p>
 */
final class StatelessDialect implements ProtocolDialect {

    private static final Logger logger = LoggerFactory.getLogger(StatelessDialect.class);

    private final McpProtocolHandler core;

    StatelessDialect(McpProtocolHandler core) {
        this.core = core;
    }

    @Override
    public JsonRpc.Response dispatch(McpRequestContext ctx) {
        var method = ctx.method();

        // server/discover is the one method that must answer regardless of the
        // version the client guessed — it exists precisely so the client can
        // learn what we support. Every other method is gated on a version match.
        if (McpMethod.SERVER_DISCOVER.equals(method)) {
            return complete(handleDiscover(ctx.id()));
        }

        var requested = ctx.protocolVersion();
        if (!Mcp2026.VERSION.equals(requested)) {
            return unsupportedVersion(ctx.id(), requested);
        }

        logger.debug("Stateless MCP request '{}' from {} (v{})",
                method, ctx.clientName(), requested);

        return switch (method) {
            case McpMethod.PING -> complete(JsonRpc.Response.success(ctx.id(), Map.of()));
            case McpMethod.TOOLS_LIST -> complete(core.handleToolsList(ctx.id()));
            case McpMethod.TOOLS_CALL ->
                    complete(core.handleToolsCall(ctx.resource(), ctx.id(), ctx.params()));
            case McpMethod.RESOURCES_LIST -> complete(core.handleResourcesList(ctx.id()));
            case McpMethod.RESOURCES_READ -> complete(core.handleResourcesRead(ctx.id(), ctx.params()));
            case McpMethod.PROMPTS_LIST -> complete(core.handlePromptsList(ctx.id()));
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
        // DiscoverResult extends CacheableResult, so ttlMs + cacheScope are
        // required. Tools/resources/prompts can be (un)registered at runtime via
        // McpRegistry, which makes capabilities mutable — advertise ttlMs:0
        // (always revalidate) rather than a cache window we cannot honor
        // (Runtime Truth). cacheScope is public: capabilities are not
        // principal-specific. The broader SEP-2549 cache metadata on
        // list/read results is a later slice.
        result.put(Mcp2026.CACHE_TTL_MS, 0L);
        result.put(Mcp2026.CACHE_SCOPE, Mcp2026.CACHE_SCOPE_PUBLIC);
        return JsonRpc.Response.success(id, result);
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
     * successful result (servers on this revision MUST include it; errors are
     * passed through untouched).
     */
    private static JsonRpc.Response complete(JsonRpc.Response response) {
        if (response.error() != null || !(response.result() instanceof Map<?, ?> existing)) {
            return response;
        }
        var withType = new LinkedHashMap<String, Object>();
        withType.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_COMPLETE);
        existing.forEach((k, v) -> withType.put(String.valueOf(k), v));
        return JsonRpc.Response.success(response.id(), withType);
    }
}
