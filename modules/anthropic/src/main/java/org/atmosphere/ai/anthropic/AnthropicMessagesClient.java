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
 *   <li>Native virtual filesystem: the {@code memory_20250818} tool is
 *       declared (as {@code {"type":"memory_20250818","name":"memory"}},
 *       no beta header) when the harness FILESYSTEM feature scoped an
 *       {@link org.atmosphere.ai.fs.AgentFileSystem} onto the session and
 *       {@link org.atmosphere.ai.fs.FilesystemMode} allows a native
 *       surface; its six commands are serviced by
 *       {@link AnthropicMemoryTool} against Atmosphere's bounded store.</li>
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

    private final String anthropicVersion;
    /**
     * Opt-in framework-level generation overrides. Never {@code null} — the
     * builder defaults it to {@link org.atmosphere.ai.GenerationParams#defaults()}
     * (all unset). {@code temperature}, {@code top_p}, and {@code stop_sequences}
     * are applied in {@link #buildRequestBody} when set; {@code maxTokens} is
     * handled by the runtime's builder wiring (it feeds the base
     * {@code max_tokens} field, preserving the {@code anthropic.max.tokens}
     * sysprop precedence). An unset component leaves the request body
     * byte-identical to today.
     */
    private final org.atmosphere.ai.GenerationParams generation;

    private AnthropicMessagesClient(Builder b) {
        super(new SseClientConfig(b.baseUrl, b.apiKey, b.httpClient, b.timeout,
                b.maxTokens, b.customHeaders, b.retryPolicy));
        this.anthropicVersion = b.anthropicVersion;
        this.generation = b.generation != null
                ? b.generation : org.atmosphere.ai.GenerationParams.defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the framework-level generation overrides applied to every
     * request body. Never {@code null}. Package-private so the wiring tests can
     * assert that {@link AnthropicAgentRuntime#createNativeClient} threaded the
     * {@code AiConfig} generation through without reaching into private state.
     */
    org.atmosphere.ai.GenerationParams generationForTest() {
        return generation;
    }

    /**
     * Returns the resolved {@code max_tokens} this client emits on every
     * request. Package-private — exists so the wiring tests can pin the
     * sysprop / GenerationParams / default precedence the runtime resolves.
     */
    int maxTokensForTest() {
        return maxTokens;
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
        // Per-request tool-loop policy controls both the iteration cap and the
        // overflow behavior, exactly like the Built-in OpenAiCompatibleClient.
        // fromOrDefault returns ToolLoopPolicy.DEFAULT (5,
        // COMPLETE_WITHOUT_TOOLS) when no policy is attached, so callers that
        // do not opt in keep the pre-policy behavior bit-identical.
        var loopPolicy = ToolLoopPolicies.fromOrDefault(context);
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

        // Provider-native structured output: the pipeline stamps the apply flag +
        // generated schema when the request declares a response type and
        // NativeStructuredOutputMode is not DISABLED. Resolved once per request;
        // a NativeStructuredOutputMode.AUTO graceful fall-back re-dispatches with
        // the flag cleared (so nativeSchema becomes null) if Anthropic rejects it.
        var nativeSchema = context != null && context.responseType() != null
                && org.atmosphere.ai.NativeStructuredOutput.shouldApply(context)
                ? org.atmosphere.ai.NativeStructuredOutput.schema(context) : null;

        // Native VFS (AiCapability.VIRTUAL_FILESYSTEM): the harness FILESYSTEM
        // feature reaches this runtime through the session's injectables —
        // AiEndpointHandler scopes an AgentFileSystem per conversation at
        // dispatch, and the AgentFileSystemProvider fallback covers
        // resource-free paths. When the store is present (and FilesystemMode
        // allows a native surface) the memory_20250818 tool is declared on
        // every round of this request and its commands are serviced against
        // the store. Resolved per request, from the live session — never from
        // configuration intent (Correctness Invariant #5, Runtime Truth).
        var memoryFs = resolveMemoryFileSystem(context, session);

        // Prompt caching (AiCapability.PROMPT_CACHING): the portable CacheHint
        // rides on context.metadata() (seeded by @AiEndpoint.promptCache, the
        // PromptCacheDefaults init-param, or the harness CACHE feature) and is
        // realized here as Anthropic cache_control breakpoints. Resolved once
        // per request and applied identically on every tool-loop round so the
        // cached prefix stays byte-stable across rounds — a breakpoint that
        // moved between rounds would invalidate the very prefix it marks.
        var cacheHint = org.atmosphere.ai.llm.CacheHint.from(context);

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
            var requestBody = buildRequestBody(model, working, system, context.tools(),
                    nativeSchema, memoryFs != null, cacheHint);
            HttpRequest httpRequest;
            try {
                httpRequest = buildHttpRequest(requestBody);
            } catch (RuntimeException e) {
                session.error(e);
                return;
            }

            var roundOutcome = parseRound(httpRequest, context, session, effectiveCancel, memoryFs);
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
                                    AtomicBoolean cancelled,
                                    org.atmosphere.ai.fs.AgentFileSystem memoryFs) {
        // Per-content-block accumulators keyed by SSE index (Anthropic
        // assigns a stable index per content_block_start within a message).
        // text_delta entries accumulate into a single text buffer; tool_use
        // entries accumulate their partial_json into a single JSON string.
        var textBuffers = new LinkedHashMap<Integer, StringBuilder>();
        var toolBuffers = new LinkedHashMap<Integer, ToolCallAccumulator>();
        // Reasoning ("thinking") blocks stream as first-class AiEvents so
        // clients that sell extended thinking can actually render it
        // (registre#16): the indexes of open thinking blocks plus their
        // accumulated integrity signatures.
        var thinkingBlocks = new java.util.HashSet<Integer>();
        var thinkingSignatures = new LinkedHashMap<Integer, StringBuilder>();
        // Mutable holder so the per-event mapper lambda can update the running
        // usage across message_delta events (Anthropic streams usage twice).
        var usageHolder = new TokenUsage[1];

        // The shared base owns the HTTP send, non-2xx error string, and the
        // data:-framed readLine scaffolding; this lambda is the only
        // Anthropic-specific part — the per-event switch.
        var completed = runRound(httpRequest, session, cancelled, event -> {
            var type = event.path("type").asString("");
            switch (type) {
                case "content_block_start" -> handleContentBlockStart(event, session,
                        textBuffers, toolBuffers, thinkingBlocks);
                case "content_block_delta" -> handleContentBlockDelta(event, session,
                        textBuffers, toolBuffers, thinkingSignatures);
                case "content_block_stop" -> {
                    // Text/tool finalisation stays lazy; a closing thinking
                    // block emits its terminal reasoning event here.
                    var stopIndex = event.path("index").asInt(-1);
                    if (thinkingBlocks.remove(stopIndex)) {
                        var sig = thinkingSignatures.remove(stopIndex);
                        session.emit(new org.atmosphere.ai.AiEvent.ReasoningComplete(
                                sig != null && !sig.isEmpty() ? sig.toString() : null));
                    }
                }
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
            ordered.put(entry.getKey(),
                    toolUseBlock(acc.id(), acc.functionName(), acc.argumentsAsMap(MAPPER)));
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
            var args = acc.argumentsAsMap(MAPPER);
            session.emit(new AiEvent.ToolStart(acc.functionName(), args));
            String resultText;
            if (memoryFs != null && AnthropicMemoryTool.TOOL_NAME.equals(acc.functionName())) {
                // The native memory_20250818 surface owns this call —
                // resolveMemoryFileSystem() already yielded to any
                // caller-registered tool named "memory".
                resultText = AnthropicMemoryTool.execute(memoryFs, args);
            } else {
                resultText = dispatchTool(context, session, acc);
            }
            session.emit(new AiEvent.ToolResult(acc.functionName(), resultText));
            toolResults.add(toolResultBlock(acc.id(), resultText));
        }

        return new RoundOutcome(assistantBlocks, List.copyOf(toolBuffers.values()),
                toolResults, false);
    }

    private void handleContentBlockStart(JsonNode event, StreamingSession session,
                                         Map<Integer, StringBuilder> textBuffers,
                                         Map<Integer, ToolCallAccumulator> toolBuffers,
                                         java.util.Set<Integer> thinkingBlocks) {
        var index = event.path("index").asInt(-1);
        if (index < 0) {
            return;
        }
        var block = event.path("content_block");
        switch (block.path("type").asString("")) {
            case "text" -> textBuffers.put(index,
                    new StringBuilder(block.path("text").asString("")));
            case "tool_use" -> {
                var acc = new ToolCallAccumulator();
                acc.setId(block.path("id").asString(""));
                acc.setFunctionName(block.path("name").asString(""));
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
                    acc.appendArguments(seedInput.toString());
                }
                toolBuffers.put(index, acc);
            }
            case "thinking" -> {
                thinkingBlocks.add(index);
                var seed = block.path("thinking").asString("");
                if (!seed.isEmpty()) {
                    session.emit(new org.atmosphere.ai.AiEvent.ReasoningDelta(seed, false));
                }
            }
            case "redacted_thinking" -> {
                // Content is provider-encrypted; signal that reasoning
                // happened without exposing it.
                thinkingBlocks.add(index);
                session.emit(new org.atmosphere.ai.AiEvent.ReasoningDelta("", true));
            }
            default -> { /* image / unsupported — ignored */ }
        }
    }

    private void handleContentBlockDelta(JsonNode event,
                                         StreamingSession session,
                                         Map<Integer, StringBuilder> textBuffers,
                                         Map<Integer, ToolCallAccumulator> toolBuffers,
                                         Map<Integer, StringBuilder> thinkingSignatures) {
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
                        acc.appendArguments(chunk);
                        // Forward the incremental fragment so browser UIs can
                        // render partial tool-argument JSON as the model types
                        // it — same posture as CohereChatClient's
                        // tool-call-delta loop and the Built-in
                        // OpenAiCompatibleClient chat-completions loop
                        // (Correctness Invariant #7, Mode Parity). The
                        // accumulator id is set by content_block_start, which
                        // Anthropic always emits before any delta for that
                        // index; a blank id is dropped by StreamingSession's
                        // null/empty guard rather than producing a stray frame.
                        session.toolCallDelta(acc.id(), chunk);
                    }
                }
            }
            case "thinking_delta" -> {
                var chunk = delta.path("thinking").asString("");
                if (!chunk.isEmpty()) {
                    session.emit(new org.atmosphere.ai.AiEvent.ReasoningDelta(chunk, false));
                }
            }
            case "signature_delta" -> thinkingSignatures
                    .computeIfAbsent(index, k -> new StringBuilder())
                    .append(delta.path("signature").asString(""));
            default -> { /* unsupported delta — ignored */ }
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

    /**
     * Resolve the {@link org.atmosphere.ai.fs.AgentFileSystem} that backs
     * the native memory tool for this request, or {@code null} when the
     * surface must stay inactive:
     * <ul>
     *   <li>{@link org.atmosphere.ai.fs.FilesystemMode#BUILTIN} pins the
     *       portable eight-tool floor — the native memory surface never
     *       registers alongside it (no duplicate file surfaces; mirrors
     *       {@code FilesystemPreset}'s registration-time decision).</li>
     *   <li>A caller-registered tool named {@code memory} wins — declaring
     *       the native tool too would put two tools with one name on the
     *       wire.</li>
     *   <li>Otherwise the store comes from the session's injectables (see
     *       {@link AnthropicMemoryTool#resolve}); absent injectables mean
     *       the harness FILESYSTEM feature is off for this session and no
     *       tool is declared.</li>
     * </ul>
     */
    private org.atmosphere.ai.fs.AgentFileSystem resolveMemoryFileSystem(
            AgentExecutionContext context, StreamingSession session) {
        if (org.atmosphere.ai.AiConfig.resolveFilesystemMode()
                == org.atmosphere.ai.fs.FilesystemMode.BUILTIN) {
            return null;
        }
        if (context != null
                && findToolDefinition(context.tools(), AnthropicMemoryTool.TOOL_NAME) != null) {
            logger.debug("Native memory tool suppressed — a caller-registered tool "
                    + "named '{}' takes precedence", AnthropicMemoryTool.TOOL_NAME);
            return null;
        }
        return AnthropicMemoryTool.resolve(session);
    }

    private String dispatchTool(AgentExecutionContext context, StreamingSession session,
                                ToolCallAccumulator acc) {
        var definition = findToolDefinition(context.tools(), acc.functionName());
        if (definition == null) {
            return "{\"error\":\"Unknown tool: " + acc.functionName() + "\"}";
        }
        var args = acc.argumentsAsMap(MAPPER);
        var injectables = Map.<Class<?>, Object>of(StreamingSession.class, session);
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
                                    String system, List<ToolDefinition> tools,
                                    String jsonSchema, boolean withMemoryTool,
                                    org.atmosphere.ai.llm.CacheHint cacheHint) {
        var root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("stream", true);
        // Framework-level GenerationParams overrides ride through to the
        // Anthropic Messages wire when set. Anthropic names the stop field
        // stop_sequences (array). Unset components are omitted so the body
        // stays byte-identical to today. maxTokens is NOT applied here — it is
        // already folded into the base `max_tokens` field by the runtime's
        // builder wiring, which preserves the anthropic.max.tokens precedence.
        if (generation.temperature() != null) {
            root.put("temperature", generation.temperature());
        }
        if (generation.topP() != null) {
            root.put("top_p", generation.topP());
        }
        if (generation.stop() != null && !generation.stop().isEmpty()) {
            var stopArray = root.putArray("stop_sequences");
            for (var s : generation.stop()) {
                stopArray.add(s);
            }
        }
        var caching = cacheHint != null && cacheHint.enabled();
        if (system != null && !system.isBlank()) {
            if (caching) {
                // Cacheable system prompt: Anthropic accepts `system` as an
                // array of text blocks, and only the block form can carry
                // cache_control. Marking the final system block caches the
                // rendered prefix up to and including the system prompt —
                // which, given Anthropic's tools → system → messages render
                // order, covers the tool definitions too.
                var systemArray = root.putArray("system");
                var block = textBlock(system);
                block.set("cache_control", cacheControlNode(cacheHint));
                systemArray.add(block);
            } else {
                // No hint: keep the plain-string form so the request body is
                // byte-identical to the pre-caching wire shape.
                root.put("system", system);
            }
        }
        var msgs = root.putArray("messages");
        for (var m : messages) {
            msgs.add(m);
        }
        if ((tools != null && !tools.isEmpty()) || withMemoryTool) {
            var toolArray = root.putArray("tools");
            if (tools != null) {
                for (var t : tools) {
                    toolArray.add(toolDefinitionNode(t));
                }
            }
            if (withMemoryTool) {
                // Anthropic-defined tool: type + name only, no input_schema
                // (the schema is built into the model). GA namespace — no
                // beta header required.
                var memory = MAPPER.createObjectNode();
                memory.put("type", AnthropicMemoryTool.TOOL_TYPE);
                memory.put("name", AnthropicMemoryTool.TOOL_NAME);
                toolArray.add(memory);
            }
            if (caching && !toolArray.isEmpty()) {
                // Second breakpoint on the last tool definition. Tools render
                // first, so this caches the tool block on its own — valuable
                // when the system prompt changes but the (deterministic,
                // insertion-ordered) tool set does not. Two breakpoints total,
                // well inside Anthropic's four-per-request ceiling.
                var lastTool = (ObjectNode) toolArray.get(toolArray.size() - 1);
                lastTool.set("cache_control", cacheControlNode(cacheHint));
            }
        }
        if (jsonSchema != null && !jsonSchema.isBlank()) {
            // Anthropic native structured output (generally available — no beta
            // header). Verified wire shape from the Claude API structured-outputs
            // docs: output_config.format carries the JSON schema so the model
            // cannot emit non-conforming output. A malformed schema must not break
            // the request — on a parse failure we skip native enforcement and let
            // the pipeline's prompt-injection path carry the schema instead.
            //   "output_config": { "format": { "type": "json_schema", "schema": {...} } }
            try {
                var schemaNode = MAPPER.readTree(jsonSchema);
                var format = root.putObject("output_config").putObject("format");
                format.put("type", "json_schema");
                format.set("schema", schemaNode);
            } catch (RuntimeException e) {
                logger.debug("Skipping Anthropic output_config — schema not parseable", e);
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise Anthropic request body", e);
        }
    }

    /**
     * Build the Anthropic {@code cache_control} marker for a breakpoint.
     * Always {@code {"type":"ephemeral"}}; an explicit
     * {@link org.atmosphere.ai.llm.CacheHint#ttl()} of at least one hour — or
     * an {@link org.atmosphere.ai.llm.CacheHint.CachePolicy#AGGRESSIVE} policy,
     * whose documented intent is maximum reuse — additionally requests
     * Anthropic's {@code "ttl":"1h"} extended cache lifetime. Anything shorter
     * uses the 5-minute default, which is the only other TTL the Messages API
     * accepts (an arbitrary Duration cannot be forwarded verbatim).
     */
    private ObjectNode cacheControlNode(org.atmosphere.ai.llm.CacheHint hint) {
        var node = MAPPER.createObjectNode();
        node.put("type", "ephemeral");
        var wantsExtended = hint.policy()
                        == org.atmosphere.ai.llm.CacheHint.CachePolicy.AGGRESSIVE
                || hint.ttl().map(t -> t.compareTo(Duration.ofHours(1)) >= 0).orElse(false);
        if (wantsExtended) {
            node.put("ttl", "1h");
        }
        return node;
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

    /**
     * Live model enumeration against Anthropic's {@code GET /v1/models}.
     * Response shape: {@code {"data":[{"type":"model","id":"claude-...",
     * "display_name":"..."} , ...],"has_more":false,...}} — only {@code id}
     * is consumed, parsed defensively by
     * {@link org.atmosphere.ai.llm.ModelListJson} (pagination is ignored; the
     * first page covers every served model in practice, and the parser bounds
     * the result regardless).
     *
     * <p>Best-effort by contract: any transport failure, non-2xx status, or
     * unparseable body returns an empty list (logged at DEBUG) so enumeration
     * can never break discovery or dispatch. Runs with a short timeout
     * independent of the streaming timeout.</p>
     *
     * @return immutable list of model identifiers; never {@code null}
     */
    public List<String> listModels() {
        var enumTimeout = timeout.compareTo(Duration.ofSeconds(10)) < 0
                ? timeout : Duration.ofSeconds(10);
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/models"))
                .header("accept", "application/json")
                .header("anthropic-version", anthropicVersion)
                .GET()
                .timeout(enumTimeout);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("x-api-key", apiKey);
        }
        applyReservedFilteredHeaders(builder,
                Set.of("x-api-key", "anthropic-version", "content-type", "accept"));
        try {
            var response = httpClient.send(builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.debug("Anthropic model enumeration returned {}", response.statusCode());
                return List.of();
            }
            return org.atmosphere.ai.llm.ModelListJson.parse(MAPPER, response.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.debug("Anthropic model enumeration interrupted", ie);
            return List.of();
        } catch (Exception e) {
            logger.debug("Anthropic model enumeration failed", e);
            return List.of();
        }
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

    /** Per-round outcome — assistant blocks, raw tool_use accumulators, and
     *  the materialised tool_result blocks ready for the next round. */
    private record RoundOutcome(
            List<ObjectNode> assistantBlocks,
            List<ToolCallAccumulator> toolUses,
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
        private org.atmosphere.ai.GenerationParams generation =
                org.atmosphere.ai.GenerationParams.defaults();
        private org.atmosphere.ai.RetryPolicy retryPolicy;

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

        /**
         * HTTP-layer retry policy applied inside the shared SSE base: 429 /
         * 500 / 502 / 503 / 408 are retried with exponential backoff and a
         * {@code Retry-After} header on 429 is honored (capped at 60s).
         * Defaults to {@link org.atmosphere.ai.RetryPolicy#DEFAULT}; pass
         * {@link org.atmosphere.ai.RetryPolicy#NONE} to disable.
         */
        public Builder retryPolicy(org.atmosphere.ai.RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /**
         * Set the framework-level {@link org.atmosphere.ai.GenerationParams}.
         * Only {@code temperature}, {@code top_p}, and {@code stop} are applied
         * by the client (as {@code temperature} / {@code top_p} /
         * {@code stop_sequences} on the Messages request); {@code maxTokens} is
         * wired separately into the base {@code max_tokens} field by the
         * runtime so the {@code anthropic.max.tokens} precedence is preserved.
         * Passing {@code null} restores
         * {@link org.atmosphere.ai.GenerationParams#defaults()}.
         */
        public Builder generation(org.atmosphere.ai.GenerationParams generation) {
            this.generation = generation != null
                    ? generation : org.atmosphere.ai.GenerationParams.defaults();
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
