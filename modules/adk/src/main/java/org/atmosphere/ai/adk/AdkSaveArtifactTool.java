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
package org.atmosphere.ai.adk;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Model-facing write half of the ADK native filesystem surface. ADK ships a
 * read tool ({@code load_artifacts}) but no built-in artifact write tool, so
 * this small {@link BaseTool} closes the loop: the model calls
 * {@code save_artifact(filename, content)} and the tool routes through
 * {@link ToolContext#saveArtifact(String, Part)} — which lands in the
 * Runner-injected {@link AdkArtifactService} and therefore in Atmosphere's
 * bounded {@link org.atmosphere.ai.fs.AgentFileSystem} store.
 *
 * <p>Registered alongside {@code LoadArtifactsTool.INSTANCE} by
 * {@link AdkAgentRuntime#buildRequestRunner} when the harness FILESYSTEM
 * primitive resolves natively; never registered together with the built-in
 * portable file tools (no duplicate tools — see
 * {@link org.atmosphere.ai.fs.FilesystemMode}). Store rejections (bounds,
 * traversal, non-text payloads) come back to the model as a clear
 * {@code status=error} tool result — never a stack trace — so it can correct
 * course, matching the built-in floor's posture.</p>
 */
public final class AdkSaveArtifactTool extends BaseTool {

    /** The tool name the model calls. */
    public static final String NAME = "save_artifact";

    private static final Logger logger = LoggerFactory.getLogger(AdkSaveArtifactTool.class);

    /**
     * Create the tool. Stateless — the artifact store is resolved per
     * invocation from the {@link ToolContext}.
     */
    public AdkSaveArtifactTool() {
        super(NAME, "Create or overwrite a text file in your workspace with the given "
                + "content. Read files back with load_artifacts. Writes are size-bounded; "
                + "an over-limit write is rejected with the reason.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        var properties = new LinkedHashMap<String, Schema>();
        properties.put("filename", Schema.builder()
                .type(Type.Known.STRING)
                .description("File path relative to the workspace root")
                .build());
        properties.put("content", Schema.builder()
                .type(Type.Known.STRING)
                .description("The full new file content")
                .build());
        return Optional.of(FunctionDeclaration.builder()
                .name(NAME)
                .description(description())
                .parameters(Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(properties)
                        .required(List.of("filename", "content"))
                        .build())
                .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args,
                                                ToolContext toolContext) {
        var safeArgs = args != null ? args : Map.<String, Object>of();
        var filename = safeArgs.get("filename");
        if (filename == null || filename.toString().isBlank()) {
            return Single.just(Map.of("status", "error",
                    "error", "'filename' is required"));
        }
        if (toolContext == null) {
            return Single.just(Map.of("status", "error",
                    "error", "No tool context — the artifact service is unavailable"));
        }
        var name = filename.toString();
        var content = safeArgs.get("content");
        var text = content == null ? "" : content.toString();
        return toolContext.saveArtifact(name, Part.fromText(text))
                .andThen(Single.<Map<String, Object>>fromCallable(() ->
                        Map.of("status", "success", "result", "Wrote " + name)))
                .onErrorReturn(e -> {
                    // Surface the rejection to the model as the tool result so it
                    // can correct course (bounds / traversal messages are written
                    // for exactly this); keep the full trace at DEBUG.
                    logger.debug("save_artifact rejected for {}", name, e);
                    return Map.of("status", "error",
                            "error", e.getMessage() != null
                                    ? e.getMessage() : e.getClass().getSimpleName());
                });
    }
}
