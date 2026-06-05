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
import org.atmosphere.mcp.protocol.McpInputContext;
import org.atmosphere.mcp.protocol.McpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
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
 * <p>Long-running tools (marked {@code @McpTool(longRunning=true)}) are served
 * via the negotiated {@code io.modelcontextprotocol/tasks} extension (SEP-2663):
 * {@code tools/call} returns a {@code CreateTaskResult} handle the client polls
 * with {@code tasks/get}, plus {@code tasks/cancel}/{@code tasks/update}. There
 * is no {@code tasks/list} on the stateless model. {@code resources/subscribe}
 * remains session-only and reports {@code METHOD_NOT_FOUND}.</p>
 */
final class StatelessDialect implements ProtocolDialect {

    private static final Logger logger = LoggerFactory.getLogger(StatelessDialect.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            case McpMethod.TOOLS_CALL -> handleToolsCall(ctx);
            case McpMethod.RESOURCES_LIST -> cacheable(core.handleResourcesList(ctx.id()));
            case McpMethod.RESOURCES_READ -> cacheable(core.handleResourcesRead(ctx.id(), ctx.params()));
            case McpMethod.PROMPTS_LIST -> cacheable(core.handlePromptsList(ctx.id()));
            case McpMethod.PROMPTS_GET -> complete(core.handlePromptsGet(ctx.id(), ctx.params()));
            // Tasks extension (SEP-2663) — gated on the negotiated capability.
            case McpMethod.TASKS_GET -> handleTasksGet(ctx);
            case McpMethod.TASKS_CANCEL -> handleTasksCancel(ctx);
            case McpMethod.TASKS_UPDATE -> handleTasksUpdate(ctx);
            default -> JsonRpc.Response.error(ctx.id(), JsonRpc.METHOD_NOT_FOUND,
                    "Method '" + method + "' is not available in the "
                            + Mcp2026.VERSION + " stateless dialect");
        };
    }

    // ── Tasks extension: io.modelcontextprotocol/tasks (SEP-2663) ────────────

    /**
     * {@code tools/call} on the stateless transport. A tool flagged
     * {@code @McpTool(longRunning=true)} is materialized as a <em>task</em>
     * (server-directed, SEP-2663): if the client negotiated the tasks
     * extension we return a {@code CreateTaskResult} handle and run the tool
     * off-thread; if it did not, we reject with {@code -32003} rather than
     * block. Ordinary tools answer synchronously, and may pause for input via
     * the multi-round-trip protocol (SEP-2322): a handler that throws
     * {@code McpInputRequiredException} yields an {@code InputRequiredResult}
     * the client fulfils and retries with {@code inputResponses}. Policy
     * admission runs on every path (mode parity) so neither a task nor a paused
     * call can bypass governance.
     */
    private JsonRpc.Response handleToolsCall(McpRequestContext ctx) {
        var params = ctx.params();
        if (params == null || !params.has("name")) {
            return JsonRpc.Response.error(ctx.id(), JsonRpc.INVALID_PARAMS, "Missing tool name");
        }
        var toolName = params.get("name").stringValue();
        var toolOpt = core.registry().tool(toolName);
        if (toolOpt.isEmpty()) {
            return JsonRpc.Response.error(ctx.id(), JsonRpc.METHOD_NOT_FOUND, "Unknown tool: " + toolName);
        }
        var arguments = params.has("arguments") ? params.get("arguments") : null;

        if (core.registry().isLongRunning(toolName)) {
            // Long-running → task (SEP-2663), gated on the negotiated extension.
            if (!ctx.clientSupportsExtension(Mcp2026.EXT_TASKS)) {
                return missingTasksCapability(ctx.id());
            }
            var denial = core.checkToolPolicy(ctx.id(), toolName, arguments);
            if (denial != null) {
                return denial;
            }
            var task = core.startToolTask(ctx.resource(), toolOpt.get(), params);
            // CreateTaskResult = Result & Task with resultType:"task".
            var m = new LinkedHashMap<String, Object>();
            m.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_TASK);
            m.putAll(taskFields(task));
            return JsonRpc.Response.success(ctx.id(), m);
        }

        // Synchronous tool with multi-round-trip input support (SEP-2322): the
        // handler may pause for client input and resume on retry.
        var denial = core.checkToolPolicy(ctx.id(), toolName, arguments);
        if (denial != null) {
            return denial;
        }
        var input = inputContext(params);
        var run = core.runToolCall(ctx.resource(), toolOpt.get(), arguments, input);
        if (run.paramError() != null) {
            return JsonRpc.Response.error(ctx.id(), JsonRpc.INVALID_PARAMS, run.paramError());
        }
        if (run.needsInput()) {
            // requestState carries the responses accumulated so far so the next
            // round (on any instance) resumes without server-side state.
            return inputRequiredResult(ctx.id(), run.inputRequests(), encodeState(input.responses()));
        }
        return complete(JsonRpc.Response.success(ctx.id(), run.callResult()));
    }

    /**
     * Build the {@code InputRequiredResult} (SEP-2322): {@code resultType:"input_required"}
     * with the handler's {@code inputRequests} and an opaque {@code requestState}.
     */
    private JsonRpc.Response inputRequiredResult(Object id, Map<String, Object> inputRequests,
                                                 String requestState) {
        var m = new LinkedHashMap<String, Object>();
        m.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_INPUT_REQUIRED);
        m.put(Mcp2026.INPUT_REQUESTS, inputRequests);
        m.put(Mcp2026.REQUEST_STATE, requestState);
        return JsonRpc.Response.success(id, m);
    }

    /**
     * Assemble the accumulated input responses for this round: the prior-round
     * responses decoded from {@code requestState} plus this request's
     * {@code inputResponses}. The result is injected into the handler as an
     * {@link McpInputContext}.
     */
    private McpInputContext inputContext(JsonNode params) {
        var accumulated = new LinkedHashMap<String, Object>();
        var state = params.get(Mcp2026.REQUEST_STATE);
        if (state != null && state.isString()) {
            accumulated.putAll(decodeState(state.stringValue()));
        }
        var responses = params.get(Mcp2026.INPUT_RESPONSES);
        if (responses != null && responses.isObject()) {
            for (var e : responses.properties()) {
                accumulated.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class));
            }
        }
        return new McpInputContext(accumulated);
    }

    private static String encodeState(Map<String, Object> responses) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(responses));
        } catch (RuntimeException e) {
            logger.trace("Could not encode requestState; returning empty", e);
            return "";
        }
    }

    @SuppressWarnings("unchecked") // Jackson readValue(Map.class) is an unavoidable raw type
    private static Map<String, Object> decodeState(String state) {
        if (state == null || state.isEmpty()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(Base64.getDecoder().decode(state), Map.class);
        } catch (RuntimeException e) {
            // Malformed or forged requestState (it is client-controlled): treat
            // as no prior responses rather than fail — the handler re-requests.
            logger.debug("Ignoring unparseable requestState", e);
            return Map.of();
        }
    }

    private JsonRpc.Response handleTasksGet(McpRequestContext ctx) {
        var taskId = requireTask(ctx);
        if (taskId.error() != null) {
            return taskId.error();
        }
        var m = new LinkedHashMap<String, Object>();
        m.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_COMPLETE);
        m.putAll(taskFields(taskId.task()));
        return JsonRpc.Response.success(ctx.id(), m);
    }

    private JsonRpc.Response handleTasksCancel(McpRequestContext ctx) {
        var lookup = requireTask(ctx);
        if (lookup.error() != null) {
            return lookup.error();
        }
        if (lookup.task().status().isTerminal()) {
            return JsonRpc.Response.error(ctx.id(), JsonRpc.INVALID_PARAMS,
                    "Cannot cancel task: already in terminal status '"
                            + lookup.task().status().wireValue() + "'");
        }
        var cancelled = core.taskManager().cancel(lookup.task().taskId(), "Cancelled by request");
        var task = cancelled.orElse(lookup.task());
        var m = new LinkedHashMap<String, Object>();
        m.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_COMPLETE);
        m.putAll(taskFields(task));
        return JsonRpc.Response.success(ctx.id(), m);
    }

    private JsonRpc.Response handleTasksUpdate(McpRequestContext ctx) {
        var lookup = requireTask(ctx);
        if (lookup.error() != null) {
            return lookup.error();
        }
        // tasks/update supplies inputResponses to an input_required task. This
        // server only produces non-interactive tasks (working → terminal); no
        // tool pauses for mid-flight input yet. The interactive input_required
        // round-trip — where this would apply inputResponses and resume — lands
        // with SEP-2322. Until then a task is never awaiting input, so we
        // reject per the method's contract rather than advertise a flow we
        // don't drive.
        if (lookup.task().status() != McpTask.Status.INPUT_REQUIRED) {
            return JsonRpc.Response.error(ctx.id(), JsonRpc.INVALID_REQUEST,
                    "Task '" + lookup.task().taskId() + "' is not awaiting input (status: "
                            + lookup.task().status().wireValue() + ")");
        }
        return JsonRpc.Response.error(ctx.id(), JsonRpc.INTERNAL_ERROR,
                "Interactive input_required tasks are not yet produced by this server");
    }

    /** Result of resolving a {@code taskId} param: exactly one of task/error is set. */
    private record TaskLookup(McpTask task, JsonRpc.Response error) {}

    private TaskLookup requireTask(McpRequestContext ctx) {
        if (!ctx.clientSupportsExtension(Mcp2026.EXT_TASKS)) {
            return new TaskLookup(null, missingTasksCapability(ctx.id()));
        }
        var params = ctx.params();
        if (params == null || !params.has(Mcp2026.TASK_ID) || !params.get(Mcp2026.TASK_ID).isString()) {
            return new TaskLookup(null, JsonRpc.Response.error(ctx.id(),
                    JsonRpc.INVALID_PARAMS, "Missing taskId"));
        }
        var taskId = params.get(Mcp2026.TASK_ID).stringValue();
        var task = core.taskManager().get(taskId);
        if (task.isEmpty()) {
            return new TaskLookup(null, JsonRpc.Response.error(ctx.id(),
                    JsonRpc.INVALID_PARAMS, "Unknown task: " + taskId));
        }
        return new TaskLookup(task.get(), null);
    }

    /** Build the {@code Task} wire fields (SEP-2663), inlining result/error for terminal states. */
    private Map<String, Object> taskFields(McpTask task) {
        var m = new LinkedHashMap<String, Object>();
        m.put(Mcp2026.TASK_ID, task.taskId());
        m.put(Mcp2026.TASK_STATUS, task.status().wireValue());
        if (task.statusMessage() != null && !task.statusMessage().isEmpty()) {
            m.put(Mcp2026.TASK_STATUS_MESSAGE, task.statusMessage());
        }
        m.put(Mcp2026.TASK_TTL_MS, task.ttlMs());
        if (task.pollIntervalMs() != null) {
            m.put(Mcp2026.TASK_POLL_INTERVAL_MS, task.pollIntervalMs());
        }
        switch (task.status()) {
            case COMPLETED -> {
                if (task.result().getNow(null) instanceof Map<?, ?> rm) {
                    var resultObj = new LinkedHashMap<String, Object>();
                    resultObj.put(Mcp2026.RESULT_TYPE, Mcp2026.RESULT_TYPE_COMPLETE);
                    rm.forEach((k, v) -> resultObj.put(String.valueOf(k), v));
                    m.put(Mcp2026.TASK_RESULT, resultObj);
                }
            }
            case FAILED -> m.put(Mcp2026.TASK_ERROR, Map.of(
                    "code", JsonRpc.INTERNAL_ERROR,
                    "message", task.statusMessage() != null ? task.statusMessage() : "Task failed"));
            default -> { /* working / input_required / cancelled carry no inlined payload */ }
        }
        return m;
    }

    private JsonRpc.Response missingTasksCapability(Object id) {
        var data = new LinkedHashMap<String, Object>();
        data.put("requiredCapabilities", Map.of(Mcp2026.CAPABILITY_EXTENSIONS,
                Map.of(Mcp2026.EXT_TASKS, Map.of())));
        return JsonRpc.Response.error(id, Mcp2026.MISSING_REQUIRED_CLIENT_CAPABILITY,
                "This request requires the " + Mcp2026.EXT_TASKS + " extension", data);
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
        // SEP-2133 extensions map. Advertise the Tasks extension only when the
        // server actually has a long-running tool to materialize a task from —
        // never advertise an extension we wouldn't exercise (Runtime Truth).
        if (core.registry().hasLongRunningTools()) {
            caps.put(Mcp2026.CAPABILITY_EXTENSIONS, Map.of(Mcp2026.EXT_TASKS, Map.of()));
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
