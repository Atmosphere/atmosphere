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

import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.contextvariables.ContextVariable;
import com.microsoft.semantickernel.contextvariables.ContextVariableType;
import com.microsoft.semantickernel.orchestration.FunctionResult;
import com.microsoft.semantickernel.orchestration.InvocationContext;
import com.microsoft.semantickernel.plugin.KernelPlugin;
import com.microsoft.semantickernel.plugin.KernelPluginFactory;
import com.microsoft.semantickernel.semanticfunctions.InputVariable;
import com.microsoft.semantickernel.semanticfunctions.KernelFunction;
import com.microsoft.semantickernel.semanticfunctions.KernelFunctionArguments;
import com.microsoft.semantickernel.semanticfunctions.KernelFunctionMetadata;
import com.microsoft.semantickernel.semanticfunctions.OutputVariable;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
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
     * JSON-Schema shape the LLM sees across every runtime. Type mapping
     * preserves the Atmosphere JSON-Schema type string verbatim (SK accepts
     * "string", "integer", "number", "boolean", "object", "array").
     */
    private static List<InputVariable> toInputVariables(ToolDefinition tool) {
        var inputs = new ArrayList<InputVariable>(tool.parameters().size());
        for (var p : tool.parameters()) {
            inputs.add(new InputVariable(
                    p.name(),
                    p.type(),
                    p.description(),
                    null,
                    p.required(),
                    Collections.emptyList()));
        }
        return inputs;
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
                                                        KernelFunctionArguments arguments,
                                                        ContextVariableType<String> variableType,
                                                        InvocationContext invocationContext) {
            return Mono.fromCallable(() -> {
                var args = new HashMap<String, Object>();
                if (arguments != null) {
                    for (var entry : arguments.entrySet()) {
                        var raw = entry.getValue();
                        args.put(entry.getKey(), raw != null ? raw.getValue() : null);
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
}
