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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.code.SandboxCommand.Language;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;

/**
 * Builds the {@code code_exec} tool — the single code-as-action surface offered
 * to the model. Rather than negotiating many fine-grained tool calls, the model
 * writes a block of code; this tool runs it in the session's {@link CodeSandbox}
 * and returns a structured result (stdout, stderr, exit code) the model reads to
 * decide its next step.
 *
 * <p>The executor pulls the session-scoped sandbox from the injectables map
 * (populated when the feature is installed for the session). If no sandbox is
 * present, execution fails loud rather than silently doing nothing.</p>
 */
public final class CodeExecTool {

    /** The tool name surfaced to the model. */
    public static final String TOOL_NAME = "code_exec";

    private static final String DESCRIPTION =
            "Execute a block of code in an isolated sandbox and return its stdout, "
            + "stderr, and exit code. Use 'javascript' to drive a headless browser "
            + "with Playwright, 'bash' for shell commands, or 'python' for data work. "
            + "The sandbox persists across calls within a session: files written to "
            + "the working directory survive between calls, so you can build up state "
            + "over several steps.";

    private CodeExecTool() {
    }

    /** The {@link ToolDefinition} to register when code execution is enabled. */
    public static ToolDefinition definition() {
        return ToolDefinition.builder(TOOL_NAME, DESCRIPTION)
                .parameter("language",
                        "Interpreter: 'javascript' (node + Playwright), 'bash', or 'python'",
                        "string", true)
                .parameter("code", "The source code to execute", "string", true)
                .returnType("object")
                .executor(executor())
                .build();
    }

    private static ToolExecutor executor() {
        return new ToolExecutor() {
            @Override
            public Object execute(Map<String, Object> arguments) throws Exception {
                // No injectables available — code execution requires a session sandbox.
                return execute(arguments, Map.of());
            }

            @Override
            public Object execute(Map<String, Object> arguments,
                                  Map<Class<?>, Object> injectables) throws Exception {
                return run(arguments, injectables);
            }
        };
    }

    private static Object run(Map<String, Object> arguments, Map<Class<?>, Object> injectables)
            throws Exception {
        CodeSandbox sandbox = injectables == null
                ? null : (CodeSandbox) injectables.get(CodeSandbox.class);
        if (sandbox == null || !sandbox.isReady()) {
            throw new SandboxException(
                    "Code execution is not available for this session.");
        }

        String code = string(arguments, "code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        Language language = parseLanguage(string(arguments, "language"));

        SandboxResult result = sandbox.exec(SandboxCommand.of(language, code));

        // Stream this round live to the browser (Atmosphere transport): one
        // AgentStep describing the round, plus each artifact as a binary frame.
        // The session is injected by the runtime tool-dispatch loop; absent in
        // non-streaming callers, so guard for null.
        StreamingSession session = injectables == null
                ? null : (StreamingSession) injectables.get(StreamingSession.class);
        if (session != null) {
            streamRound(session, language, result);
        }

        var view = new LinkedHashMap<String, Object>();
        view.put("exitCode", result.exitCode());
        view.put("stdout", result.stdout());
        view.put("stderr", result.stderr());
        if (result.truncated()) {
            view.put("truncated", true);
        }
        if (result.timedOut()) {
            view.put("timedOut", true);
        }
        if (!result.artifacts().isEmpty()) {
            view.put("artifactCount", result.artifacts().size());
        }
        return view;
    }

    /**
     * Push one code-execution round to the client: an {@link AiEvent.AgentStep}
     * summarizing the round, then each artifact as a binary content frame
     * (images via {@link Content.Image}, everything else via {@link Content.File}).
     */
    private static void streamRound(StreamingSession session, Language language, SandboxResult result) {
        var data = new LinkedHashMap<String, Object>();
        data.put("language", language.name().toLowerCase(Locale.ROOT));
        data.put("exitCode", result.exitCode());
        data.put("artifacts", result.artifacts().size());
        if (result.timedOut()) {
            data.put("timedOut", true);
        }
        session.emit(new AiEvent.AgentStep("code_exec",
                "Ran " + language.name().toLowerCase(Locale.ROOT)
                        + " (exit " + result.exitCode() + ")", data));

        for (SandboxArtifact artifact : result.artifacts()) {
            if (artifact.mimeType().startsWith("image/")) {
                // Render screenshots inline by streaming a markdown data-URI image.
                // The Atmosphere Console renders message markdown (via marked.js),
                // which turns ![alt](data:...) into an <img>; it has no renderer for
                // typed Content.Image binary frames, so a data-URI is what actually
                // displays the screenshot to the user.
                String b64 = java.util.Base64.getEncoder().encodeToString(artifact.data());
                session.send("\n\n![" + artifact.name() + "](data:"
                        + artifact.mimeType() + ";base64," + b64 + ")\n\n");
            } else {
                session.sendContent(new Content.File(
                        artifact.data(), artifact.mimeType(), artifact.name()));
            }
        }
    }

    /** Map a model-supplied language string to an interpreter; default JavaScript. */
    static Language parseLanguage(String value) {
        if (value == null) {
            return Language.JAVASCRIPT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "bash", "sh", "shell" -> Language.BASH;
            case "python", "python3", "py" -> Language.PYTHON;
            default -> Language.JAVASCRIPT;
        };
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }
}
