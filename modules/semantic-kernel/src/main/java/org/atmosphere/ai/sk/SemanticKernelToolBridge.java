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
package org.atmosphere.ai.sk;

import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinitionFunction;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.FunctionCall;
import com.azure.core.util.BinaryData;
import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.contextvariables.ContextVariable;
import com.microsoft.semantickernel.contextvariables.ContextVariableType;
import com.microsoft.semantickernel.orchestration.FunctionResult;
import com.microsoft.semantickernel.orchestration.InvocationContext;
import com.microsoft.semantickernel.plugin.KernelPlugin;
import com.microsoft.semantickernel.plugin.KernelPluginFactory;
import com.microsoft.semantickernel.semanticfunctions.InputVariable;
import com.microsoft.semantickernel.semanticfunctions.KernelFunction;
import com.microsoft.semantickernel.semanticfunctions.KernelArguments;
import com.microsoft.semantickernel.semanticfunctions.KernelFunctionMetadata;
import com.microsoft.semantickernel.semanticfunctions.OutputVariable;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.tool.ToolBridgeUtils;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges Atmosphere's framework-agnostic {@link ToolDefinition} registry into
 * Microsoft Semantic Kernel's {@link KernelFunction} / {@link KernelPlugin}
 * model. Each Atmosphere tool becomes one {@link AtmosphereSkFunction} —
 * a direct {@link KernelFunction} subclass that overrides
 * {@link KernelFunction#invokeAsync} and routes every invocation through
 * {@link ToolExecutionHelper#executeWithApproval} so {@code @RequiresApproval}
 * gates fire uniformly with the other runtime bridges (Correctness Invariant
 * #7, Mode Parity).
 *
 * <p>This is the "best design" path identified by the capability-honesty
 * analysis: SK's {@link KernelFunction} base class is explicitly designed for
 * subclassing (protected constructor, abstract {@code invokeAsync}), so we can
 * create one function per Atmosphere tool without any reflection, annotation
 * processing, or bytecode synthesis — contradicting the now-stale class
 * Javadoc on {@code SemanticKernelAgentRuntime} which claimed such a bridge
 * was impossible without a compile-time processor.</p>
 */
final class SemanticKernelToolBridge {

    private static final Logger logger = LoggerFactory.getLogger(SemanticKernelToolBridge.class);

    private static final String PLUGIN_NAME = "atmosphere_tools";

    private SemanticKernelToolBridge() {
    }

    /**
     * Build a {@link KernelPlugin} containing one SK {@link KernelFunction}
     * per Atmosphere {@link ToolDefinition} on the execution context.
     * Returns {@code null} when the context carries no tools so callers can
     * skip plugin wiring altogether on the zero-tool fast path.
     */
    static KernelPlugin buildPlugin(AgentExecutionContext context,
                                    StreamingSession session) {
        var tools = context.tools();
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        var functions = new ArrayList<KernelFunction<?>>(tools.size());
        for (var tool : tools) {
            functions.add(new AtmosphereSkFunction(
                    tool, session,
                    context.approvalStrategy(),
                    context.approvalPolicy()));
        }
        logger.debug("SK tool bridge: built {} KernelFunction(s) for plugin '{}'",
                functions.size(), PLUGIN_NAME);
        return KernelPluginFactory.createFromFunctions(PLUGIN_NAME, functions);
    }

    /**
     * Translate Atmosphere's {@link ToolParameter} list into SK
     * {@link InputVariable}s so SK's function metadata advertises the same
     * JSON-Schema shape the LLM sees across every runtime. The type goes
     * through {@link #skType(String)}: SK takes it for a Java type name, not a
     * JSON-Schema type.
     */
    private static List<InputVariable> toInputVariables(ToolDefinition tool) {
        var inputs = new ArrayList<InputVariable>(tool.parameters().size());
        for (var p : tool.parameters()) {
            // InputVariable's trailing argument is the enum value list
            // (getEnumValues); passing the parameter's closed set instead of an
            // empty list is what tells the planner which values are legal.
            inputs.add(new InputVariable(
                    p.name(),
                    skType(p.type()),
                    p.description(),
                    null,
                    p.required(),
                    p.enumValues().isEmpty() ? Collections.emptyList() : p.enumValues()));
        }
        return inputs;
    }

    /**
     * The {@link InputVariable} type Semantic Kernel 1.5.0 turns into the
     * JSON-Schema type {@code jsonType}. SK reads the type as a Java type name
     * ({@code OpenAIFunction.getJavaTypeToOpenAiFunctionType}): {@code string},
     * {@code integer}, {@code boolean} and {@code array} it maps as they are,
     * but anything else it takes for a class whose schema it generates, through
     * {@code KernelPluginFactory.getTypeForName}, which knows no {@code number}
     * and no {@code object} and throws. SK builds the schema of every function
     * on each model call, so one such parameter failed every tool turn of the
     * endpoint before the model call. {@code double} gives {@code number}. An
     * {@code object} would need SK's optional schema generator
     * ({@code com.github.victools:jsonschema-generator}), so it is declared as
     * {@code string} to SK; the schema the model sees is replaced on the wire
     * by {@link #sendToolsUnderTheirOwnNames}. An unknown type is declared as
     * {@code string}, SK's own default, rather than failing every tool.
     */
    static String skType(String jsonType) {
        if (jsonType == null) {
            return "string";
        }
        return switch (jsonType.toLowerCase(java.util.Locale.ROOT)) {
            case "string" -> "string";
            case "integer" -> "integer";
            case "number" -> "double";
            case "boolean" -> "boolean";
            case "array" -> "array";
            // SK builds an object's schema with an optional generator a project
            // seldom has; the wire schema comes from sendToolsUnderTheirOwnNames.
            case "object" -> "string";
            default -> {
                logger.debug("SK tool bridge: parameter type '{}' advertised as string", jsonType);
                yield "string";
            }
        };
    }

    /**
     * {@link KernelFunction} subclass that wraps a single Atmosphere
     * {@link ToolDefinition} and routes LLM-triggered invocations through
     * {@link ToolExecutionHelper#executeWithApproval}. Using a direct
     * subclass (rather than {@code KernelFunctionFromMethod} reflection)
     * avoids the Java-Method + target-object indirection and lets the bridge
     * translate {@link KernelFunctionArguments} into Atmosphere's
     * {@code Map<String, Object>} shape on every invocation.
     */
    static final class AtmosphereSkFunction extends KernelFunction<String> {

        private final ToolDefinition tool;
        private final StreamingSession session;
        private final ApprovalStrategy approvalStrategy;
        private final ToolApprovalPolicy approvalPolicy;

        AtmosphereSkFunction(ToolDefinition tool,
                             StreamingSession session,
                             ApprovalStrategy approvalStrategy,
                             ToolApprovalPolicy approvalPolicy) {
            super(
                    new KernelFunctionMetadata<>(
                            PLUGIN_NAME,
                            tool.name(),
                            tool.description(),
                            toInputVariables(tool),
                            new OutputVariable<>(
                                    "Result of the " + tool.name() + " tool",
                                    String.class)),
                    Collections.emptyMap());
            this.tool = tool;
            this.session = session;
            this.approvalStrategy = approvalStrategy;
            this.approvalPolicy = approvalPolicy != null
                    ? approvalPolicy
                    : ToolApprovalPolicy.annotated();
        }

        @Override
        public Mono<FunctionResult<String>> invokeAsync(Kernel kernel,
                                                        KernelArguments arguments,
                                                        ContextVariableType<String> variableType,
                                                        InvocationContext invocationContext) {
            return Mono.fromCallable(() -> {
                var args = new HashMap<String, Object>();
                if (arguments != null) {
                    for (var entry : arguments.entrySet()) {
                        var raw = entry.getValue();
                        args.put(entry.getKey(), coerce(tool, entry.getKey(),
                                raw != null ? raw.getValue() : null));
                    }
                }
                // Route through the shared approval helper so @RequiresApproval
                // gates fire uniformly with Built-in / Spring AI / LC4j / ADK /
                // Koog. The return is always a String on this SPI path —
                // downstream LLMs receive the stringified tool result as the
                // next assistant turn's input.
                var result = ToolExecutionHelper.executeWithApproval(
                        tool.name(), tool, args, session, approvalStrategy, approvalPolicy);
                var value = result != null ? result : "";
                var contextVariable = ContextVariable.of(value);
                return new FunctionResult<>(contextVariable, value);
            });
        }
    }

    /**
     * Give a model-supplied argument the JSON type its parameter declares.
     * Semantic Kernel hands every tool-call argument over as text
     * ({@code {"a":2}} arrives as {@code "2"}), and the shared argument
     * validation rejects a {@code String} for an {@code integer},
     * {@code number} or {@code boolean} parameter. Text that does not parse
     * stays as it is, so the validation reports it; {@code object} and
     * {@code array} parameters take the JSON text as it is.
     */
    static Object coerce(ToolDefinition tool, String name, Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        var type = tool.parameters().stream()
                .filter(p -> p.name().equals(name))
                .map(ToolParameter::type)
                .findFirst()
                .orElse(null);
        if (type == null) {
            return value;
        }
        var trimmed = text.trim();
        try {
            return switch (type) {
                case "integer" -> new java.math.BigDecimal(trimmed).longValueExact();
                case "number" -> Double.parseDouble(trimmed);
                case "boolean" -> "true".equalsIgnoreCase(trimmed) ? Boolean.TRUE
                        : "false".equalsIgnoreCase(trimmed) ? Boolean.FALSE : value;
                default -> value;
            };
        } catch (NumberFormatException | ArithmeticException e) {
            logger.trace("SK tool {} argument {} is not a {}: {}", tool.name(), name, type, e.getMessage());
            return value;
        }
    }

    /**
     * Install, on the per-request {@code kernel}, the hooks that send the
     * tools of {@code plugin} to the model under their own names
     * ({@code assess_emergency}) instead of Semantic Kernel's
     * {@code <plugin>-<function>} ({@code atmosphere_tools-assess_emergency}),
     * and give the model's tool calls their qualified name back before Semantic
     * Kernel reads them. SK 1.5.0 hard-codes the {@code -} separator
     * ({@code OpenAIFunction.getNameSeparator()}) and needs the plugin part to
     * find the function, so the renaming happens on the wire: on Ollama with
     * qwen2.5 a hyphenated tool name makes the model's tool call unparseable,
     * and the turn ended with no text and no tool call. The names are the
     * tools' own, as the built-in runtime sends them, and the parameter schema
     * is the one {@link ToolBridgeUtils#buildJsonSchemaString} builds (as the
     * Spring AI bridge sends it: enum values, array items, object properties),
     * in place of SK's, which
     * knows no array items and needs an optional generator for an object.
     *
     * <p>The request hook renames the tool definitions and the tool calls of
     * the assistant messages already in the history (a follow-up round of the
     * loop carries them); the response hook renames the tool calls of the
     * answer in place, before SK splits them into plugin and function. A name
     * the model sends qualified, or that no tool of the plugin carries, is left
     * as it is.</p>
     */
    static void sendToolsUnderTheirOwnNames(Kernel kernel, KernelPlugin plugin, List<ToolDefinition> tools) {
        var qualifiedByName = new HashMap<String, String>();
        var nameByQualified = new HashMap<String, String>();
        var schemaByQualified = new HashMap<String, String>();
        for (var tool : tools) {
            if (!plugin.getFunctions().containsKey(tool.name())) {
                continue;
            }
            var qualified = plugin.getName() + "-" + tool.name();
            qualifiedByName.put(tool.name(), qualified);
            nameByQualified.put(qualified, tool.name());
            schemaByQualified.put(qualified, ToolBridgeUtils.buildJsonSchemaString(tool.parameters()));
        }
        kernel.getGlobalKernelHooks().addPreChatCompletionHook(event -> {
            var options = event.getOptions();
            var definitions = options.getTools();
            if (definitions != null) {
                var renamed = new ArrayList<ChatCompletionsToolDefinition>(definitions.size());
                for (var sent : definitions) {
                    renamed.add(sent instanceof ChatCompletionsFunctionToolDefinition definition
                            && nameByQualified.containsKey(definition.getFunction().getName())
                            ? withName(definition, nameByQualified.get(definition.getFunction().getName()),
                                    schemaByQualified.get(definition.getFunction().getName()))
                            : sent);
                }
                options.setTools(renamed);
            }
            for (var message : options.getMessages()) {
                if (message instanceof ChatRequestAssistantMessage assistant && assistant.getToolCalls() != null) {
                    var calls = new ArrayList<>(assistant.getToolCalls());
                    calls.replaceAll(call -> renamed(call, nameByQualified));
                    assistant.setToolCalls(calls);
                }
            }
            return event;
        });
        kernel.getGlobalKernelHooks().addPostChatCompletionHook(event -> {
            var choices = event.getChatCompletions().getChoices();
            for (var choice : choices != null ? choices : List.<ChatChoice>of()) {
                var message = choice.getMessage();
                var calls = message != null ? message.getToolCalls() : null;
                if (calls == null || calls.isEmpty()) {
                    continue;
                }
                try {
                    calls.replaceAll(call -> renamed(call, qualifiedByName));
                } catch (UnsupportedOperationException e) {
                    logger.warn("SK tool bridge: cannot give the model's tool calls their qualified name back;"
                            + " Semantic Kernel will not find {}", calls, e);
                }
            }
            return event;
        });
    }

    private static ChatCompletionsFunctionToolDefinition withName(
            ChatCompletionsFunctionToolDefinition definition, String name, String schema) {
        var function = definition.getFunction();
        return new ChatCompletionsFunctionToolDefinition(
                new ChatCompletionsFunctionToolDefinitionFunction(name)
                        .setDescription(function.getDescription())
                        .setParameters(BinaryData.fromString(schema))
                        .setStrict(function.isStrict()));
    }

    private static ChatCompletionsToolCall renamed(ChatCompletionsToolCall call, Map<String, String> names) {
        if (call instanceof ChatCompletionsFunctionToolCall functionCall
                && functionCall.getFunction() != null
                && names.containsKey(functionCall.getFunction().getName())) {
            return new ChatCompletionsFunctionToolCall(functionCall.getId(), new FunctionCall(
                    names.get(functionCall.getFunction().getName()), functionCall.getFunction().getArguments()));
        }
        return call;
    }

    /**
     * Install, on the per-request {@code kernel}, the hook that stops Semantic
     * Kernel's auto-invoke loop once a tool round ended the turn (an approval
     * that ran out with the turn's time limit, a cancel): it runs before every
     * model call of the loop and throws {@link TurnEndedException} instead of
     * letting the call go out. A tool cannot stop the loop itself: SK folds a
     * tool's exception into a tool message and asks the model again.
     *
     * <p>SK's loop retries a failed round once more before giving up, so the
     * hook throws twice (SK logs each as a failed tool invocation attempt);
     * neither reaches the model.</p>
     */
    static void stopLoopOnceTurnEnded(Kernel kernel, StreamingSession session) {
        kernel.getGlobalKernelHooks().addPreChatCompletionHook(event -> {
            if (session.isClosed() || session.isTurnCancelled()) {
                throw new TurnEndedException();
            }
            return event;
        });
    }

    /**
     * Thrown before a model call of Semantic Kernel's auto-invoke loop once a
     * tool round ended the turn, so the loop issues no follow-up model call;
     * the runtime takes it as the end of the turn, not as an error.
     */
    static final class TurnEndedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TurnEndedException() {
            super("The turn ended in a tool round", null, false, false);
        }

        /** Whether {@code error} or one of its causes is a {@link TurnEndedException}. */
        static boolean endedTheTurn(Throwable error) {
            for (var t = error; t != null; t = t.getCause()) {
                if (t instanceof TurnEndedException) {
                    return true;
                }
            }
            return false;
        }
    }
}
