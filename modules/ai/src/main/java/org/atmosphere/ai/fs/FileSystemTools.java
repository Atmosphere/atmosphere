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
package org.atmosphere.ai.fs;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
import org.atmosphere.ai.tool.ToolKind;
import org.atmosphere.ai.tool.ToolScopes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Builds the built-in file tools — the portable virtual-filesystem floor
 * every runtime gets when the harness FILESYSTEM feature resolves and no
 * native file surface wins (see {@link FilesystemMode}): {@code ls},
 * {@code read_file}, {@code glob}, {@code grep} ({@link ToolKind#READ}),
 * {@code write_file}, {@code edit_file}, {@code rename} ({@link ToolKind#EDIT})
 * and {@code delete} ({@link ToolKind#DELETE}).
 *
 * <p>Each executor is a thin wrapper over the conversation-scoped
 * {@link AgentFileSystem} resolved from the injectables seam. Bounds and
 * traversal rejections ({@link IllegalArgumentException} with a clear
 * message) are returned to the model as the tool result — never a stack
 * trace — so it can correct course.</p>
 */
public final class FileSystemTools {

    /** The {@code ls} tool name. */
    public static final String LS = "ls";

    /** The {@code read_file} tool name. */
    public static final String READ_FILE = "read_file";

    /** The {@code write_file} tool name. */
    public static final String WRITE_FILE = "write_file";

    /** The {@code edit_file} tool name. */
    public static final String EDIT_FILE = "edit_file";

    /** The {@code glob} tool name. */
    public static final String GLOB = "glob";

    /** The {@code grep} tool name. */
    public static final String GREP = "grep";

    /** The {@code delete} tool name. */
    public static final String DELETE = "delete";

    /** The {@code rename} tool name. */
    public static final String RENAME = "rename";

    private FileSystemTools() {
    }

    /**
     * All eight built-in file tools, in registration order.
     *
     * @return the tool definitions, never empty
     */
    public static List<ToolDefinition> all() {
        return List.of(ls(), readFile(), writeFile(), editFile(), glob(), grep(),
                delete(), rename());
    }

    /** The {@code ls} tool: list a directory in the agent workspace. */
    public static ToolDefinition ls() {
        return ToolDefinition.builder(LS,
                        "List the files and directories in your workspace. Directories are "
                        + "suffixed with '/'. Paths are relative to the workspace root.")
                .parameter("dir", "Directory to list, relative to the workspace root; "
                        + "omit or '.' for the root", "string", false)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var entries = fs.ls(str(args, "dir"));
                    if (entries.isEmpty()) {
                        return "(empty)";
                    }
                    var out = new StringJoiner("\n");
                    for (var entry : entries) {
                        out.add(entry.directory() ? entry.path() + "/"
                                : entry.path() + " (" + entry.size() + " bytes)");
                    }
                    return out.toString();
                }))
                .kind(ToolKind.READ)
                .build();
    }

    /** The {@code read_file} tool: read one file's full content. */
    public static ToolDefinition readFile() {
        return ToolDefinition.builder(READ_FILE,
                        "Read a file from your workspace and return its full text content.")
                .parameter("path", "File path relative to the workspace root", "string", true)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> fs.read(required(args, "path"))))
                .kind(ToolKind.READ)
                .build();
    }

    /** The {@code write_file} tool: create or replace a file. */
    public static ToolDefinition writeFile() {
        return ToolDefinition.builder(WRITE_FILE,
                        "Create or overwrite a file in your workspace with the given text "
                        + "content. Parent directories are created automatically. Writes are "
                        + "size-bounded; an over-limit write is rejected with the reason.")
                .parameter("path", "File path relative to the workspace root", "string", true)
                .parameter("content", "The full new file content", "string", true)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var path = required(args, "path");
                    var content = args.get("content");
                    fs.write(path, content == null ? "" : content.toString());
                    return "Wrote " + path;
                }))
                .kind(ToolKind.EDIT)
                .build();
    }

    /** The {@code edit_file} tool: unique-match replace inside a file. */
    public static ToolDefinition editFile() {
        return ToolDefinition.builder(EDIT_FILE,
                        "Edit a file in your workspace by replacing old_text with new_text. "
                        + "old_text must match EXACTLY ONCE - include enough surrounding "
                        + "context to make it unique. Zero or multiple matches are errors.")
                .parameter("path", "File path relative to the workspace root", "string", true)
                .parameter("old_text", "The exact text to replace (must occur exactly once)",
                        "string", true)
                .parameter("new_text", "The replacement text", "string", true)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var path = required(args, "path");
                    var newText = args.get("new_text");
                    fs.edit(path, required(args, "old_text"),
                            newText == null ? "" : newText.toString());
                    return "Edited " + path;
                }))
                .kind(ToolKind.EDIT)
                .build();
    }

    /**
     * The {@code delete} tool: remove a file from the workspace. Tagged
     * {@link ToolKind#DELETE} so a {@code ToolApprovalPolicy} can gate it —
     * the blast radius is bounded to the conversation-scoped workspace, but
     * deletion is destructive, so operators may choose to require approval.
     */
    public static ToolDefinition delete() {
        return ToolDefinition.builder(DELETE,
                        "Delete a file from your workspace. The path is validated at the "
                        + "boundary and must be relative to the workspace root.")
                .parameter("path", "File path relative to the workspace root", "string", true)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var path = required(args, "path");
                    fs.delete(path);
                    return "Deleted " + path;
                }))
                .kind(ToolKind.DELETE)
                .build();
    }

    /**
     * The {@code rename} tool: move a file within the workspace. Both paths
     * are validated at the boundary.
     */
    public static ToolDefinition rename() {
        return ToolDefinition.builder(RENAME,
                        "Rename or move a file within your workspace. Both paths are "
                        + "validated and must be relative to the workspace root.")
                .parameter("old_path", "Current file path relative to the workspace root",
                        "string", true)
                .parameter("new_path", "New file path relative to the workspace root",
                        "string", true)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var oldPath = required(args, "old_path");
                    var newPath = required(args, "new_path");
                    fs.rename(oldPath, newPath);
                    return "Renamed " + oldPath + " to " + newPath;
                }))
                .kind(ToolKind.EDIT)
                .build();
    }

    /** The {@code glob} tool: find files by name pattern. */
    public static ToolDefinition glob() {
        return ToolDefinition.builder(GLOB,
                        "Find files in your workspace whose relative path matches a glob "
                        + "pattern, e.g. '**/*.md' or 'notes/*.txt'.")
                .parameter("pattern", "The glob pattern to match against relative paths",
                        "string", true)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var matches = fs.glob(required(args, "pattern"));
                    return matches.isEmpty() ? "(no matches)" : String.join("\n", matches);
                }))
                .kind(ToolKind.READ)
                .build();
    }

    /** The {@code grep} tool: search file contents by regex. */
    public static ToolDefinition grep() {
        return ToolDefinition.builder(GREP,
                        "Search the text content of your workspace files with a regular "
                        + "expression. Returns matching lines as path:line: text. Output is "
                        + "capped at " + WorkspaceAgentFileSystem.MAX_GREP_HITS + " hits.")
                .parameter("pattern", "The regular expression to search for", "string", true)
                .parameter("dir", "Directory to search under, relative to the workspace "
                        + "root; omit for the whole workspace", "string", false)
                .returnType("string")
                .executor(fsExecutor((fs, args) -> {
                    var hits = fs.grep(required(args, "pattern"), str(args, "dir"));
                    if (hits.isEmpty()) {
                        return "(no matches)";
                    }
                    var out = new StringJoiner("\n");
                    for (var hit : hits) {
                        out.add(hit.path() + ":" + hit.line() + ": " + hit.text());
                    }
                    return out.toString();
                }))
                .kind(ToolKind.READ)
                .build();
    }

    /** One file-tool operation against the resolved filesystem. */
    private interface FsOperation {
        Object apply(AgentFileSystem fs, Map<String, Object> args);
    }

    /**
     * Wrap an operation with the shared resolution + error surface: the
     * conversation-scoped {@link AgentFileSystem} comes straight from the
     * injectables when the dispatch seam published it, else is derived from
     * the registered {@link AgentFileSystemProvider} and the conversation id
     * (resource-free pipeline paths). Traversal / bounds rejections come
     * back as clear tool-result strings.
     */
    private static ToolExecutor fsExecutor(FsOperation operation) {
        return new ToolExecutor() {
            @Override
            public Object execute(Map<String, Object> arguments) throws Exception {
                return execute(arguments, Map.of());
            }

            @Override
            public Object execute(Map<String, Object> arguments,
                                  Map<Class<?>, Object> injectables) throws Exception {
                var scope = injectables != null ? injectables : Map.<Class<?>, Object>of();
                var fs = resolve(scope);
                if (fs == null) {
                    return "File tools unavailable: no agent filesystem is bound to "
                            + "this session.";
                }
                try {
                    return operation.apply(fs,
                            arguments != null ? arguments : Map.of());
                } catch (IllegalArgumentException e) {
                    return "Error: " + e.getMessage();
                }
            }
        };
    }

    private static AgentFileSystem resolve(Map<Class<?>, Object> scope) {
        if (scope.get(AgentFileSystem.class) instanceof AgentFileSystem fs) {
            return fs;
        }
        if (scope.get(AgentFileSystemProvider.class) instanceof AgentFileSystemProvider provider) {
            return provider.forConversation(ToolScopes.conversationId(scope));
        }
        return null;
    }

    /**
     * Resolve the conversation-scoped {@link AgentFileSystem} for a tool
     * invocation from the injectables seam — the same resolution the built-in
     * file tools use: an explicit {@link AgentFileSystem} entry wins, else it
     * is derived from a registered {@link AgentFileSystemProvider} and the
     * scope's {@link ToolScopes#conversationId(Map) conversation id}. Exposed
     * so other tool-execution machinery (e.g. large-tool-output disk offload
     * in {@code ToolExecutionHelper}) resolves the identical store without
     * duplicating the lookup.
     *
     * @param scope the dispatch-time injectables map (may be {@code null})
     * @return the resolved filesystem, or {@link Optional#empty()} when none
     *         is bound to this scope
     */
    public static Optional<AgentFileSystem> resolveFileSystem(Map<Class<?>, Object> scope) {
        return Optional.ofNullable(scope == null ? null : resolve(scope));
    }

    private static String required(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value.toString();
    }

    private static String str(Map<String, Object> args, String name) {
        var value = args.get(name);
        return value == null ? null : value.toString();
    }
}
