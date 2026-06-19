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
package org.atmosphere.ai.cohere;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.llm.AbstractSseLlmClient;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.llm.ToolCallAccumulator;
import org.atmosphere.ai.llm.ToolLoopGuard;
import org.atmosphere.ai.llm.ToolLoopPolicies;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateless HTTP client for the Cohere v2 Chat API
 * ({@code POST /v2/chat}). Owns the SSE parse and the client-side tool
 * loop; everything observability-related (JFR, AiMetrics, approval gates)
 * is delegated to the {@link CohereAgentRuntime} caller and the shared
 * {@link ToolExecutionHelper}.
 *
 * <p>Wire shape verified against the Cohere v2 Chat API docs:</p>
 * <ul>
 *   <li>Headers: {@code Authorization: Bearer &lt;key&gt;},
 *       {@code Content-Type: application/json},
 *       {@code Accept: text/event-stream}.</li>
 *   <li>Required body fields: {@code model}, {@code messages},
 *       {@code stream: true}.</li>
 *   <li>Optional body fields used here: {@code max_tokens},
 *       {@code temperature}, {@code tools}.</li>
 *   <li>Message roles: {@code system}, {@code user}, {@code assistant},
 *       {@code tool} (tool result carries {@code tool_call_id} and
 *       {@code content}).</li>
 *   <li>SSE events: {@code message-start}, {@code content-start},
 *       {@code content-delta} (text in {@code delta.message.content.text}),
 *       {@code content-end}, {@code tool-plan-delta},
 *       {@code tool-call-start} (carries
 *       {@code delta.message.tool_calls[N]} with {@code id}, {@code function.name}),
 *       {@code tool-call-delta} (argument fragment in
 *       {@code delta.message.tool_calls[N].function.arguments}),
 *       {@code tool-call-end}, {@code citation-start},
 *       {@code citation-end}, {@code message-end} (carries
 *       {@code delta.usage.billed_units}/{@code tokens} and
 *       {@code delta.finish_reason}).</li>
 * </ul>
 *
 * <p>Vision / multi-modal input is staged for the end-of-phase parity
 * pass — text and tool calling ship first per the
 * {@code docs/audits/vision-parity-2026-05-22.md} plan.</p>
 */
public final class CohereChatClient extends AbstractSseLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(CohereChatClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.cohere.com";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private CohereChatClient(Builder b) {
        super(new SseClientConfig(b.baseUrl, b.apiKey, b.httpClient, b.timeout,
                b.maxTokens, b.customHeaders));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected String providerName() {
        return "Cohere";
    }

    /**
     * Run one or more rounds of: build request → POST → SSE-parse → if the
     * model emitted tool calls, dispatch them and feed
     * {@code role: "tool"} result messages back into the next round.
     * Forwards text deltas via {@link StreamingSession#send} and tool
     * start/result frames via {@link AiEvent}. Calls
     * {@link StreamingSession#complete} once the final round emits no
     * further tool requests.
     *
     * @param model    Cohere model identifier (e.g. {@code command-a-plus-05-2026})
     * @param history  conversation history threaded by the framework
     * @param system   system prompt; null or blank is skipped
     * @param userMessage incoming user turn text
     * @param context  pipeline execution context (carries tools, approval
     *                 policy, strategy, injectables, listeners — exactly the
     *                 surface {@link ToolExecutionHelper#executeWithApproval}
     *                 needs)
     * @param session  streaming sink the runtime writes through
     * @param cancelled cooperative cancel flag set by the caller's handle
     */
    public void stream(String model,
                       List<ChatMessage> history,
                       String system,
                       String userMessage,
                       AgentExecutionContext context,
                       StreamingSession session,
                       AtomicBoolean cancelled) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(session, "session");
        var effectiveCancel = cancelled != null ? cancelled : new AtomicBoolean();
        // Per-request tool-loop policy controls both the iteration cap and the
        // overflow behavior, exactly like the Built-in OpenAiCompatibleClient.
        // fromOrDefault returns ToolLoopPolicy.DEFAULT (5,
        // COMPLETE_WITHOUT_TOOLS) when no policy is attached, so callers that
        // do not opt in keep the pre-policy behavior bit-identical.
        var loopPolicy = ToolLoopPolicies.fromOrDefault(context);
        var rounds = 0;
        // Working messages list starts with optional system prompt + history,
        // then accumulates assistant + tool messages across rounds.
        var working = new ArrayList<ObjectNode>();
        if (system != null && !system.isBlank()) {
            working.add(textMessage("system", system));
        }
        for (var msg : (history != null ? history : List.<ChatMessage>of())) {
            var converted = toCohereMessage(msg);
            if (converted != null) {
                working.add(converted);
            }
        }
        var parts = context != null ? context.parts() : List.<org.atmosphere.ai.Content>of();
        if (!parts.isEmpty()) {
            // Multi-modal path: Cohere v2 chat expects a content array when
            // any part of the message is non-text.
            working.add(userMessageWithParts(userMessage, parts));
        } else if (userMessage != null && !userMessage.isEmpty()) {
            // Text-only fast path: keep the legacy string-content shape so
            // existing wire-format assertions and lighter-weight inference
            // backends that don't parse content arrays continue to work.
            working.add(textMessage("user", userMessage));
        }

        while (true) {
            if (effectiveCancel.get()) {
                session.error(new InterruptedException("Cancelled before round " + rounds));
                return;
            }
            switch (ToolLoopGuard.checkRoundCap(rounds, loopPolicy, session, providerName())) {
                case CONTINUE -> { /* below the cap — run the round below */ }
                case FAILED -> {
                    // checkRoundCap already fired session.error(...).
                    return;
                }
                case COMPLETE_WITHOUT_TOOLS -> {
                    session.complete();
                    return;
                }
            }
            var requestBody = buildRequestBody(model, working, context.tools());
            HttpRequest httpRequest;
            try {
                httpRequest = buildHttpRequest(requestBody);
            } catch (RuntimeException e) {
                session.error(e);
                return;
            }

            var roundOutcome = parseRound(httpRequest, context, session, effectiveCancel);
            if (roundOutcome.errored()) {
                // The round already emitted session.error / session.complete.
                return;
            }
            working.add(buildAssistantMessage(roundOutcome.assistantText(),
                    roundOutcome.toolCalls()));
            if (roundOutcome.toolCalls().isEmpty()) {
                session.complete();
                return;
            }
            for (var entry : roundOutcome.toolResults().entrySet()) {
                working.add(toolResultMessage(entry.getKey(), entry.getValue()));
            }
            rounds++;
        }
    }

    private RoundOutcome parseRound(HttpRequest httpRequest,
                                    AgentExecutionContext context,
                                    StreamingSession session,
                                    AtomicBoolean cancelled) {
        var assistantText = new StringBuilder();
        // Tool-call accumulators keyed by tool-call index (Cohere assigns
        // a stable index on tool-call-start within a stream).
        var toolBuffers = new LinkedHashMap<Integer, ToolCallAccumulator>();
        // (ToolCallAccumulator is the shared org.atmosphere.ai.llm type; the
        // Cohere-specific tool-call index is the local map key, not a field.)
        // Mutable holder so the per-event mapper lambda can update the running
        // usage across message-end events.
        var usageHolder = new TokenUsage[1];

        // The shared base owns the HTTP send, non-2xx error string, and the
        // data:-framed readLine scaffolding; this lambda is the only
        // Cohere-specific part — the per-event switch.
        var completed = runRound(httpRequest, session, cancelled, event -> {
            var type = event.path("type").asString("");
            switch (type) {
                case "content-delta" -> handleContentDelta(event, session, assistantText);
                case "tool-call-start" -> handleToolCallStart(event, toolBuffers);
                case "tool-call-delta" -> handleToolCallDelta(event, session, toolBuffers);
                case "message-end" -> {
                    var parsed = parseUsage(event, usageHolder[0]);
                    if (parsed != null) {
                        usageHolder[0] = parsed;
                    }
                }
                // Lifecycle / RAG events with no SPI mapping yet — silently
                // tracked so future capability passes can wire them up.
                case "message-start", "content-start", "content-end",
                        "tool-plan-delta", "tool-call-end",
                        "citation-start", "citation-end" -> { /* no-op */ }
                default -> logger.trace("Unhandled Cohere SSE event: {}", type);
            }
        });
        if (!completed) {
            // runRound already emitted session.error for the IO/non-2xx paths;
            // the cancel path leaves the session untouched (matches original).
            return RoundOutcome.failure();
        }

        var usage = usageHolder[0];
        if (usage != null && usage.hasCounts()) {
            session.usage(usage);
        }

        // Dispatch every tool call via the shared ToolExecutionHelper so
        // @RequiresApproval gates, ToolPermissionPolicy, and JFR events fire
        // uniformly across runtimes.
        var toolResults = new LinkedHashMap<String, String>();
        var toolCalls = new ArrayList<ToolCallAccumulator>();
        for (var entry : toolBuffers.entrySet()) {
            var acc = entry.getValue();
            toolCalls.add(acc);
            var args = acc.argumentsAsMap(MAPPER);
            session.emit(new AiEvent.ToolStart(acc.functionName(), args));
            var resultText = dispatchTool(context, session, acc, args);
            session.emit(new AiEvent.ToolResult(acc.functionName(), resultText));
            toolResults.put(acc.id(), resultText);
        }

        return new RoundOutcome(assistantText.toString(), toolCalls, toolResults, false);
    }

    private void handleContentDelta(JsonNode event,
                                    StreamingSession session,
                                    StringBuilder assistantText) {
        // Cohere streams text under delta.message.content.text. Defensive
        // path-walk: the SDK has shifted these field names across betas
        // (content.text vs content[0].text) — accept either.
        var delta = event.path("delta").path("message").path("content");
        String chunk = "";
        if (delta.isObject() && delta.has("text")) {
            chunk = delta.path("text").asString("");
        } else if (delta.isArray() && delta.size() > 0) {
            chunk = delta.get(0).path("text").asString("");
        }
        if (!chunk.isEmpty()) {
            session.send(chunk);
            assistantText.append(chunk);
        }
    }

    private void handleToolCallStart(JsonNode event,
                                     Map<Integer, ToolCallAccumulator> toolBuffers) {
        var index = event.path("index").asInt(-1);
        if (index < 0) {
            return;
        }
        var toolCall = event.path("delta").path("message").path("tool_calls");
        // Cohere documents tool_calls as an object in delta on tool-call-start;
        // some SDK versions ship an array — accept either.
        JsonNode call = toolCall.isArray() && toolCall.size() > 0
                ? toolCall.get(0) : toolCall;
        if (call.isMissingNode() || call.isNull()) {
            return;
        }
        var acc = new ToolCallAccumulator();
        acc.setId(call.path("id").asString(""));
        acc.setFunctionName(call.path("function").path("name").asString(""));
        var seedArgs = call.path("function").path("arguments");
        if (seedArgs.isString()) {
            acc.appendArguments(seedArgs.asString(""));
        } else if (seedArgs.isObject() && !seedArgs.isEmpty()) {
            acc.appendArguments(seedArgs.toString());
        }
        toolBuffers.put(index, acc);
    }

    private void handleToolCallDelta(JsonNode event,
                                     StreamingSession session,
                                     Map<Integer, ToolCallAccumulator> toolBuffers) {
        var index = event.path("index").asInt(-1);
        var toolCall = event.path("delta").path("message").path("tool_calls");
        JsonNode call = toolCall.isArray() && toolCall.size() > 0
                ? toolCall.get(0) : toolCall;
        var chunk = call.path("function").path("arguments").asString("");
        if (!chunk.isEmpty()) {
            var acc = toolBuffers.get(index);
            if (acc != null) {
                acc.appendArguments(chunk);
                // Forward the incremental fragment so browser UIs can render
                // partial tool-argument JSON as the model types it — same
                // posture as OpenAiCompatibleClient's chat-completions loop.
                // The accumulator id is set by tool-call-start; if Cohere ever
                // ships a tool-call-delta before tool-call-start (it does not
                // today), the empty id is dropped by StreamingSession's null/
                // empty guard rather than producing a stray frame.
                session.toolCallDelta(acc.id(), chunk);
            }
        }
    }

    private TokenUsage parseUsage(JsonNode event, TokenUsage prior) {
        // Cohere reports usage under two parallel shapes:
        //   delta.usage.billed_units.{input_tokens,output_tokens}
        //   delta.usage.tokens.{input_tokens,output_tokens}
        // Both can appear together; prefer the `tokens` block (raw counts)
        // when present, since `billed_units` reflects pricing model and
        // may exclude cached tokens.
        var usage = event.path("delta").path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return prior;
        }
        var tokens = usage.has("tokens") ? usage.get("tokens")
                : usage.path("billed_units");
        if (tokens.isMissingNode() || tokens.isNull()) {
            return prior;
        }
        long input = tokens.has("input_tokens") ? tokens.get("input_tokens").asLong()
                : (prior != null ? prior.input() : 0L);
        long output = tokens.has("output_tokens") ? tokens.get("output_tokens").asLong()
                : (prior != null ? prior.output() : 0L);
        long cached = usage.has("cached_tokens") ? usage.get("cached_tokens").asLong()
                : (prior != null ? prior.cachedInput() : 0L);
        return TokenUsage.fromCounts(input, output, cached, null);
    }

    private String dispatchTool(AgentExecutionContext context,
                                StreamingSession session,
                                ToolCallAccumulator acc,
                                Map<String, Object> args) {
        var definition = findToolDefinition(context.tools(), acc.functionName());
        if (definition == null) {
            return "{\"error\":\"Unknown tool: " + acc.functionName() + "\"}";
        }
        // Forward the session's framework injectables (AgentFleet, AgentIdentity,
        // CodeSandbox, ...) to the tool, plus the live session — mirroring the
        // built-in runtime's OpenAiCompatibleClient. Without this, @AiTool methods
        // that declare framework types (or the code_exec tool's CodeSandbox) would
        // be unavailable on the Cohere path (Correctness Invariant #7 — Mode Parity).
        var injectables = new java.util.LinkedHashMap<Class<?>, Object>(session.injectables());
        injectables.putIfAbsent(StreamingSession.class, session);
        var approvalPolicy = context.approvalPolicy() != null
                ? context.approvalPolicy() : ToolApprovalPolicy.annotated();
        return ToolExecutionHelper.executeWithApproval(
                acc.functionName(), definition, args, session,
                context.approvalStrategy(), approvalPolicy, injectables);
    }

    private static ToolDefinition findToolDefinition(List<ToolDefinition> tools, String name) {
        if (tools == null || name == null) {
            return null;
        }
        for (var tool : tools) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }

    private ObjectNode toCohereMessage(ChatMessage msg) {
        if (msg == null || msg.role() == null) {
            return null;
        }
        return switch (msg.role()) {
            case "user" -> textMessage("user", msg.content() != null ? msg.content() : "");
            case "assistant" -> textMessage("assistant", msg.content() != null ? msg.content() : "");
            case "system" -> textMessage("system", msg.content() != null ? msg.content() : "");
            case "tool" -> toolResultMessage(msg.toolCallId() != null ? msg.toolCallId() : "",
                    msg.content() != null ? msg.content() : "");
            default -> null;
        };
    }

    private ObjectNode textMessage(String role, String text) {
        var message = MAPPER.createObjectNode();
        message.put("role", role);
        message.put("content", text != null ? text : "");
        return message;
    }

    /**
     * Assemble a user-role message that combines optional text with any
     * multi-modal parts. Cohere v2 chat uses OpenAI-compatible content
     * arrays — text blocks ({@code {"type":"text"}}) and image_url blocks
     * ({@code {"type":"image_url","image_url":{"url":"data:..."}}}) — when
     * a single message carries multiple parts.
     *
     * <p>{@link org.atmosphere.ai.Content.Image} translates to an
     * {@code image_url} block with a base64 data URI;
     * {@link org.atmosphere.ai.Content.Audio} and
     * {@link org.atmosphere.ai.Content.File} are dropped with a debug log
     * because the Cohere v2 chat content array has no audio or file block
     * type. Declaring AUDIO without the wire path would lie about runtime
     * truth.</p>
     */
    private ObjectNode userMessageWithParts(String text, List<org.atmosphere.ai.Content> parts) {
        var message = MAPPER.createObjectNode();
        message.put("role", "user");
        var content = message.putArray("content");
        if (text != null && !text.isEmpty()) {
            var textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
        }
        for (var part : parts) {
            if (part instanceof org.atmosphere.ai.Content.Image img) {
                var imageBlock = content.addObject();
                imageBlock.put("type", "image_url");
                var imageUrl = imageBlock.putObject("image_url");
                imageUrl.put("url", "data:" + img.mimeType() + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(img.data()));
            } else if (part instanceof org.atmosphere.ai.Content.Text t) {
                var textBlock = content.addObject();
                textBlock.put("type", "text");
                textBlock.put("text", t.text());
            } else {
                logger.debug("Dropping unsupported multi-modal part {} — "
                        + "Cohere v2 chat content array has no matching block type",
                        part.getClass().getSimpleName());
            }
        }
        return message;
    }

    private ObjectNode toolResultMessage(String toolCallId, String content) {
        var message = MAPPER.createObjectNode();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId != null ? toolCallId : "");
        message.put("content", content != null ? content : "");
        return message;
    }

    private ObjectNode buildAssistantMessage(String text, List<ToolCallAccumulator> toolCalls) {
        var message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", text != null ? text : "");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            var array = message.putArray("tool_calls");
            for (var call : toolCalls) {
                var node = array.addObject();
                node.put("id", call.id());
                node.put("type", "function");
                var fn = node.putObject("function");
                fn.put("name", call.functionName());
                fn.put("arguments", call.arguments());
            }
        }
        return message;
    }

    private String buildRequestBody(String model, List<ObjectNode> messages,
                                    List<ToolDefinition> tools) {
        var root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("stream", true);
        var msgs = root.putArray("messages");
        for (var m : messages) {
            msgs.add(m);
        }
        if (tools != null && !tools.isEmpty()) {
            var toolArray = root.putArray("tools");
            for (var t : tools) {
                toolArray.add(toolDefinitionNode(t));
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise Cohere request body", e);
        }
    }

    private ObjectNode toolDefinitionNode(ToolDefinition def) {
        var root = MAPPER.createObjectNode();
        root.put("type", "function");
        var function = root.putObject("function");
        function.put("name", def.name());
        function.put("description", def.description());
        // Wrap the shared inner schema object (built by the base) in Cohere's
        // {@code function.parameters} envelope.
        function.set("parameters", toolSchemaObjectNode(def, MAPPER));
        return root;
    }

    private HttpRequest buildHttpRequest(String body) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v2/chat"))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(timeout);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("authorization", "Bearer " + apiKey);
        }
        // Custom headers carry observability / proxy / tenant metadata.
        // Reserved protocol headers are filtered out — same posture as
        // OpenAiCompatibleClient.applyCustomHeaders and
        // AnthropicMessagesClient.buildHttpRequest.
        applyReservedFilteredHeaders(builder,
                Set.of("authorization", "content-type", "accept"));
        return builder.build();
    }

    /** Per-round outcome — accumulated assistant text, tool calls dispatched
     *  during the round, and the {@code tool_call_id → result} map ready for
     *  the next round. */
    private record RoundOutcome(
            String assistantText,
            List<ToolCallAccumulator> toolCalls,
            Map<String, String> toolResults,
            boolean errored) {
        static RoundOutcome failure() {
            return new RoundOutcome("", List.of(), Map.of(), true);
        }
    }

    /** Builder for {@link CohereChatClient}. */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private HttpClient httpClient;
        private Duration timeout = Duration.ofSeconds(120);
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private final LinkedHashMap<String, String> customHeaders = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder customHeader(String name, String value) {
            if (name != null && !name.isBlank() && value != null) {
                this.customHeaders.put(name, value);
            }
            return this;
        }

        public Builder customHeaders(Map<String, String> headers) {
            this.customHeaders.clear();
            if (headers != null) {
                for (var entry : headers.entrySet()) {
                    customHeader(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public CohereChatClient build() {
            return new CohereChatClient(this);
        }
    }
}
