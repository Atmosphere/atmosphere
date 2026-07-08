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
package org.atmosphere.ai.code;

import java.util.Map;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
import org.atmosphere.ai.tool.ToolKind;

/**
 * Builds the {@code eval} tool — an in-process JavaScript evaluator for fast,
 * container-free computation. It is the lightweight counterpart to
 * {@code code_exec}: where {@code code_exec} spins a container for shell /
 * Python / browser work, {@code eval} runs pure ECMAScript in a sandboxed Rhino
 * scope with no host, file, or network access and no cross-call state — ideal
 * for arithmetic, data shaping, and JSON/string manipulation.
 *
 * <p>Tagged {@link ToolKind#EXECUTE} so a {@code ToolApprovalPolicy} or
 * governance policy can gate it exactly like other code-execution surfaces. The
 * evaluator itself is default-off (see {@link EvalConfig}); this definition is
 * only registered when {@link EvalSupport#isEnabled()}.</p>
 */
public final class EvalTool {

    /** The tool name surfaced to the model. */
    public static final String TOOL_NAME = "eval";

    private static final String DESCRIPTION =
            "Evaluate a JavaScript expression or snippet in a fast, isolated in-process "
            + "sandbox and return its result. Use it for calculation, data transformation, "
            + "and JSON/string work — no container, file, network, or system access is "
            + "involved, and no state persists between calls. The value of the final "
            + "expression is returned (objects and arrays as JSON).";

    private EvalTool() {
    }

    /** The {@link ToolDefinition} to register when {@link EvalSupport#isEnabled()}. */
    public static ToolDefinition definition() {
        return ToolDefinition.builder(TOOL_NAME, DESCRIPTION)
                .parameter("code", "The JavaScript source to evaluate", "string", true)
                .returnType("string")
                .executor(executor())
                .kind(ToolKind.EXECUTE)
                .build();
    }

    private static ToolExecutor executor() {
        return new ToolExecutor() {
            @Override
            public Object execute(Map<String, Object> arguments) throws Exception {
                return execute(arguments, Map.of());
            }

            @Override
            public Object execute(Map<String, Object> arguments,
                                  Map<Class<?>, Object> injectables) throws Exception {
                Object code = arguments == null ? null : arguments.get("code");
                EvalResult result = EvalSupport.shared()
                        .evaluate(code == null ? null : code.toString());
                if (!result.ok()) {
                    return "Error: " + result.error();
                }
                return result.truncated()
                        ? result.value() + "\n…(output truncated)"
                        : result.value();
            }
        };
    }
}
