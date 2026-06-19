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
package org.atmosphere.ai.anthropic;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.llm.AbstractSseLlmClient;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * Stateless HTTP client for the Anthropic Messages API
 * ({@code POST /v1/messages}). Owns the streaming SSE parse and the
 * client-side tool loop; everything observability-related (JFR,
 * AiMetrics, approval gates) is delegated to the {@link AnthropicAgentRuntime}
 * caller and the shared {@link ToolExecutionHelper}.
 *
 * <p>Wire shape verified against the Anthropic Messages API docs:</p>
 * <ul>
 *   <li>Headers: {@code x-api-key}, {@code anthropic-version} (default
 *       {@code 2023-06-01}), {@code content-type: application/json}.</li>
 *   <li>Required body fields: {@code model}, {@code max_tokens},
 *       {@code messages}.</li>
 *   <li>Optional body fields used here: {@code system}, {@code tools},
 *       {@code stream}, {@code temperature}.</li>
 *   <li>Content block types: {@code text}, {@code tool_use},
 *       {@code tool_result}.</li>
 *   <li>SSE events: {@code message_start}, {@code content_block_start},
 *       {@code content_block_delta} (with {@code text_delta} or
 *       {@code input_json_delta}), {@code content_block_stop},
 *       {@code message_delta} (carries {@code usage.output_tokens} and
 *       {@code delta.stop_reason}), {@code message_stop}.</li>
 * </ul>
 */
public final class AnthropicMessagesClient extends AbstractSseLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicMessagesClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int DEFAULT_TOOL_ROUND_CAP = 5;

    private final String anthropicVersion;

    private AnthropicMessagesClient(Builder b) {
        super(new SseClientConfig(b.baseUrl, b.apiKey, b.httpClient, b.timeout,
                b.maxTokens, b.customHeaders));
        this.anthropicVersion = b.anthropicVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected String providerName() {
        return "Anthropic";
    }

    /**
     * Run one or more rounds of: build request → POST → SSE-parse → if the
     * model emitted {@code tool_use} blocks, dispatch them and feed
     * {@code tool_result} blocks back into the next round. Forwards text
     * deltas via {@link StreamingSession#send} and tool start/result frames
     * via {@link AiEvent}. Calls {@link StreamingSession#complete} once the
     * final round emits no further tool requests.
     *
     * @param model    Anthropic model identifier (e.g. {@code claude-sonnet-4-6})
     * @param history  conversation history threaded by the framework
     * @param system   system prompt; null or blank is sent as an empty system
     * @param userMessage incoming user turn text
     * @param context  pipeline execution context (carries tools, approval policy,
     *                 strategy, injectables, listeners — exactly the surface
     *                 {@link ToolExecutionHelper#executeWithApproval} needs)
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
        var rounds = 0;
        // Working message list starts with the framework-managed history
        // converted to Anthropic blocks, then accumulates assistant
        // tool_use blocks and tool_result blocks across rounds.
        var working = new ArrayList<ObjectNode>();
        for (var msg : (history != null ? history : List.<ChatMessage>of())) {
            var converted = toAnthropicMessage(msg);
            if (converted != null) {
                working.add(converted);
            }
        }
        var parts = context != null ? context.parts() : List.<org.atmosphere.ai.Content>of();
        if (!parts.isEmpty()) {
            // Multi-modal path: a user message that interleaves text + image
            // blocks must carry a content array.
            working.add(userMessageWithParts(userMessage, parts));
        } else if (userMessage != null && !userMessage.isEmpty()) {
            // Text-only fast path preserves the legacy single-block shape so
            // existing wire-format assertions continue to hold.
            working.add(textMessage("user", userMessage));
        }

        while (true) {
            if (effectiveCancel.get()) {
                session.error(new InterruptedException("Cancelled before round " + rounds));
                return;
            }
            if (rounds > DEFAULT_TOOL_ROUND_CAP) {
                logger.warn("Anthropic tool loop cap ({}) reached — completing without tools",
                        DEFAULT_TOOL_ROUND_CAP);
                session.complete();
                return;
            }
            var requestBody = buildRequestBody(model, working, system, context.tools());
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
            // Append the assistant message that contained the (possibly
            // tool_use) blocks plus any tool_result blocks we just computed.
            working.add(buildAssistantBlocks(roundOutcome.assistantBlocks()));
            if (roundOutcome.toolUses().isEmpty()) {
                session.complete();
                return;
            }
            working.add(buildToolResultsBlocks(roundOutcome.toolResults()));
            rounds++;
        }
    }

    private RoundOutcome parseRound(HttpRequest httpRequest,
                                    AgentExecutionContext context,
                                    StreamingSession session,
                                    AtomicBoolean cancelled) {
        // Per-content-block accumulators keyed by SSE index (Anthropic
        // assigns a stable index per content_block_start within a message).
        // text_delta entries accumulate into a single text buffer; tool_use
        // entries accumulate their partial_json into a single JSON string.
        var textBuffers = new LinkedHashMap<Integer, StringBuilder>();
        var toolBuffers = new LinkedHashMap<Integer, ToolUseAccumulator>();
        // Mutable holder so the per-event mapper lambda can update the running
        // usage across message_delta events (Anthropic streams usage twice).
        var usageHolder = new TokenUsage[1];

        // The shared base owns the HTTP send, non-2xx error string, and the
        // data:-framed readLine scaffolding; this lambda is the only
        // Anthropic-specific part — the per-event switch.
        var completed = runRound(httpRequest, session, cancelled, event -> {
            var type = event.path("type").asString("");
            switch (type) {
                case "content_block_start" -> handleContentBlockStart(event, textBuffers, toolBuffers);
                case "content_block_delta" -> handleContentBlockDelta(event, session, textBuffers, toolBuffers);
                case "content_block_stop" -> { /* finalisation handled lazily */ }
                case "message_delta" -> {
                    var parsed = parseMessageDelta(event, usageHolder[0]);
                    if (parsed != null) {
                        usageHolder[0] = parsed;
                    }
                }
                case "message_start", "message_stop", "ping" -> { /* no-op */ }
                default -> logger.trace("Unhandled Anthropic SSE event: {}", type);
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

        // Materialise the assistant blocks in original index order so the
        // tool round-trip preserves Anthropic's expectation that the
        // tool_use blocks in the assistant message keep their original
        // id mapping for the subsequent tool_result lookup.
        var assistantBlocks = new ArrayList<ObjectNode>();
        var ordered = new LinkedHashMap<Integer, ObjectNode>();
        for (var entry : textBuffers.entrySet()) {
            ordered.put(entry.getKey(), textBlock(entry.getValue().toString()));
        }
        for (var entry : toolBuffers.entrySet()) {
            var acc = entry.getValue();
            ordered.put(entry.getKey(), toolUseBlock(acc.id, acc.name, acc.parseInput(MAPPER)));
        }
        // Insertion order in LinkedHashMap is not numeric — sort by index.
        ordered.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> assistantBlocks.add(e.getValue()));

        // Dispatch any tool_use blocks via the shared ToolExecutionHelper
        // so @RequiresApproval gates, ToolPermissionPolicy, and JFR events
        // fire uniformly across runtimes.
        var toolResults = new ArrayList<ObjectNode>();
        for (var entry : toolBuffers.entrySet()) {
            var acc = entry.getValue();
            session.emit(new AiEvent.ToolStart(acc.name, acc.parseInput(MAPPER)));
            var resultText = dispatchTool(context, session, acc);
            session.emit(new AiEvent.ToolResult(acc.name, resultText));
            toolResults.add(toolResultBlock(acc.id, resultText));
        }

        return new RoundOutcome(assistantBlocks, List.copyOf(toolBuffers.values()),
                toolResults, false);
    }

    private void handleContentBlockStart(JsonNode event,
                                         Map<Integer, StringBuilder> textBuffers,
                                         Map<Integer, ToolUseAccumulator> toolBuffers) {
        var index = event.path("index").asInt(-1);
        if (index < 0) {
            return;
        }
        var block = event.path("content_block");
        switch (block.path("type").asString("")) {
            case "text" -> textBuffers.put(index,
                    new StringBuilder(block.path("text").asString("")));
            case "tool_use" -> {
                var acc = new ToolUseAccumulator();
                acc.id = block.path("id").asString("");
                acc.name = block.path("name").asString("");
                // Anthropic's streaming protocol always sends an empty
                // `"input":{}` placeholder on content_block_start and then
                // streams the real JSON via input_json_delta events. Seeding
                // from the placeholder would concatenate "{}" with the first
                // delta and produce invalid JSON. Only capture the seed when
                // the model returned a non-empty object up front — that path
                // exists for non-streaming responses but is never used inside
                // an SSE round.
                var seedInput = block.path("input");
                if (!seedInput.isMissingNode() && !seedInput.isNull()
                        && seedInput.isObject() && !seedInput.isEmpty()) {
                    acc.rawJson.append(seedInput.toString());
                }
                toolBuffers.put(index, acc);
            }
            default -> { /* thinking / image / unsupported — ignored */ }
        }
    }

    private void handleContentBlockDelta(JsonNode event,
                                         StreamingSession session,
                                         Map<Integer, StringBuilder> textBuffers,
                                         Map<Integer, ToolUseAccumulator> toolBuffers) {
        var index = event.path("index").asInt(-1);
        var delta = event.path("delta");
        var deltaType = delta.path("type").asString("");
        switch (deltaType) {
            case "text_delta" -> {
                var chunk = delta.path("text").asString("");
                if (!chunk.isEmpty()) {
                    session.send(chunk);
                    textBuffers.computeIfAbsent(index, k -> new StringBuilder()).append(chunk);
                }
            }
            case "input_json_delta" -> {
                var chunk = delta.path("partial_json").asString("");
                if (!chunk.isEmpty()) {
                    var acc = toolBuffers.get(index);
                    if (acc != null) {
                        acc.rawJson.append(chunk);
                    }
                }
            }
            default -> { /* thinking_delta / signature_delta — ignored for now */ }
        }
    }

    private TokenUsage parseMessageDelta(JsonNode event, TokenUsage prior) {
        var usage = event.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return prior;
        }
        long input = usage.has("input_tokens") ? usage.get("input_tokens").asLong()
                : (prior != null ? prior.input() : 0L);
        long output = usage.has("output_tokens") ? usage.get("output_tokens").asLong()
                : (prior != null ? prior.output() : 0L);
        long cached = usage.has("cache_read_input_tokens")
                ? usage.get("cache_read_input_tokens").asLong()
                : (prior != null ? prior.cachedInput() : 0L);
        return TokenUsage.fromCounts(input, output, cached, null);
    }

    private String dispatchTool(AgentExecutionContext context, StreamingSession session,
                                ToolUseAccumulator acc) {
        var definition = findToolDefinition(context.tools(), acc.name);
        if (definition == null) {
            return "{\"error\":\"Unknown tool: " + acc.name + "\"}";
        }
        var args = acc.parseInput(MAPPER);
        var injectables = Map.<Class<?>, Object>of(StreamingSession.class, session);
        var approvalPolicy = context.approvalPolicy() != null
                ? context.approvalPolicy() : ToolApprovalPolicy.annotated();
        return ToolExecutionHelper.executeWithApproval(
                acc.name, definition, args, session,
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

    private ObjectNode toAnthropicMessage(ChatMessage msg) {
        if (msg == null || msg.role() == null) {
            return null;
        }
        return switch (msg.role()) {
            case "user" -> textMessage("user", msg.content() != null ? msg.content() : "");
            case "assistant" -> textMessage("assistant", msg.content() != null ? msg.content() : "");
            case "tool" -> {
                // Anthropic carries tool_result on a user-role message.
                var message = MAPPER.createObjectNode();
                message.put("role", "user");
                var content = message.putArray("content");
                content.add(toolResultBlock(msg.toolCallId(), msg.content() != null ? msg.content() : ""));
                yield message;
            }
            // System messages are extracted to the top-level `system` field
            // by the request builder, not added to the messages array.
            case "system" -> null;
            default -> null;
        };
    }

    private ObjectNode textMessage(String role, String text) {
        var message = MAPPER.createObjectNode();
        message.put("role", role);
        var content = message.putArray("content");
        content.add(textBlock(text));
        return message;
    }

    private ObjectNode textBlock(String text) {
        var block = MAPPER.createObjectNode();
        block.put("type", "text");
        block.put("text", text != null ? text : "");
        return block;
    }

    /**
     * Anthropic Messages image block — base64 inline source. Anthropic
     * accepts {@code image/jpeg}, {@code image/png}, {@code image/gif},
     * and {@code image/webp}; mime types outside that set are forwarded
     * as-is and the API will reject them at request time.
     */
    private ObjectNode imageBlock(byte[] data, String mimeType) {
        var block = MAPPER.createObjectNode();
        block.put("type", "image");
        var source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mimeType != null ? mimeType : "application/octet-stream");
        source.put("data", java.util.Base64.getEncoder().encodeToString(data));
        return block;
    }

    /**
     * Assemble a user-role message that combines optional text with any
     * multi-modal parts. {@link org.atmosphere.ai.Content.Image} translates
     * to a native image block; {@link org.atmosphere.ai.Content.Audio} and
     * {@link org.atmosphere.ai.Content.File} are dropped with a debug log
     * because Anthropic Messages does not accept audio or arbitrary file
     * blocks today — declaring AUDIO without that wire path would lie
     * about runtime truth.
     */
    private ObjectNode userMessageWithParts(String text, List<org.atmosphere.ai.Content> parts) {
        var message = MAPPER.createObjectNode();
        message.put("role", "user");
        var content = message.putArray("content");
        if (text != null && !text.isEmpty()) {
            content.add(textBlock(text));
        }
        for (var part : parts) {
            if (part instanceof org.atmosphere.ai.Content.Image img) {
                content.add(imageBlock(img.data(), img.mimeType()));
            } else if (part instanceof org.atmosphere.ai.Content.Text t) {
                content.add(textBlock(t.text()));
            } else {
                logger.debug("Dropping unsupported multi-modal part {} — "
                        + "Anthropic Messages API has no matching content block",
                        part.getClass().getSimpleName());
            }
        }
        return message;
    }

    private ObjectNode toolUseBlock(String id, String name, Map<String, Object> input) {
        var block = MAPPER.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        block.set("input", MAPPER.valueToTree(input != null ? input : Map.of()));
        return block;
    }

    private ObjectNode toolResultBlock(String toolUseId, String content) {
        var block = MAPPER.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId != null ? toolUseId : "");
        block.put("content", content != null ? content : "");
        return block;
    }

    private ObjectNode buildAssistantBlocks(List<ObjectNode> blocks) {
        var message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        var content = message.putArray("content");
        for (var b : blocks) {
            content.add(b);
        }
        return message;
    }

    private ObjectNode buildToolResultsBlocks(List<ObjectNode> results) {
        var message = MAPPER.createObjectNode();
        message.put("role", "user");
        var content = message.putArray("content");
        for (var r : results) {
            content.add(r);
        }
        return message;
    }

    private String buildRequestBody(String model, List<ObjectNode> messages,
                                    String system, List<ToolDefinition> tools) {
        var root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("stream", true);
        if (system != null && !system.isBlank()) {
            root.put("system", system);
        }
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
            throw new RuntimeException("Failed to serialise Anthropic request body", e);
        }
    }

    private ObjectNode toolDefinitionNode(ToolDefinition def) {
        var node = MAPPER.createObjectNode();
        node.put("name", def.name());
        node.put("description", def.description());
        // ToolDefinition stores its JSON Schema fragment on the parameters
        // list. Anthropic expects an {@code input_schema} object — wrap the
        // shared inner schema object (built by the base) in Anthropic's
        // {@code input_schema} envelope so callers do not have to know
        // Anthropic's schema shape.
        node.set("input_schema", toolSchemaObjectNode(def, MAPPER));
        return node;
    }

    private HttpRequest buildHttpRequest(String body) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("content-type", "application/json")
                .header("anthropic-version", anthropicVersion)
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(timeout);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("x-api-key", apiKey);
        }
        // Custom headers carry observability / proxy / tenant metadata.
        // Reserved protocol headers are filtered out — same posture as
        // OpenAiCompatibleClient.applyCustomHeaders.
        applyReservedFilteredHeaders(builder,
                Set.of("x-api-key", "anthropic-version", "content-type", "accept"));
        return builder.build();
    }

    /** Per-tool accumulator that gathers partial_json fragments and exposes
     *  a parsed {@code Map<String, Object>} once the block is closed. */
    private static final class ToolUseAccumulator {
        String id = "";
        String name = "";
        final StringBuilder rawJson = new StringBuilder();

        Map<String, Object> parseInput(ObjectMapper mapper) {
            if (rawJson.length() == 0) {
                return Map.of();
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(rawJson.toString(), Map.class);
                return parsed != null ? parsed : Map.of();
            } catch (Exception e) {
                return Map.of("__raw", rawJson.toString());
            }
        }
    }

    /** Per-round outcome — assistant blocks, raw tool_use accumulators, and
     *  the materialised tool_result blocks ready for the next round. */
    private record RoundOutcome(
            List<ObjectNode> assistantBlocks,
            List<ToolUseAccumulator> toolUses,
            List<ObjectNode> toolResults,
            boolean errored) {
        static RoundOutcome failure() {
            return new RoundOutcome(List.of(), List.of(), List.of(), true);
        }
    }

    /** Builder for {@link AnthropicMessagesClient}. */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String anthropicVersion = DEFAULT_VERSION;
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

        public Builder anthropicVersion(String version) {
            this.anthropicVersion = version;
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

        public AnthropicMessagesClient build() {
            return new AnthropicMessagesClient(this);
        }
    }
}
