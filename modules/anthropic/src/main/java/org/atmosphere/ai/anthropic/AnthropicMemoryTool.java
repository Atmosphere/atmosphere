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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.tool.ToolScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Bridges Anthropic's client-implemented memory tool
 * ({@code memory_20250818}) onto Atmosphere's bounded, conversation-scoped
 * {@link AgentFileSystem} store — the runtime's native virtual-filesystem
 * surface ({@link org.atmosphere.ai.AiCapability#VIRTUAL_FILESYSTEM}).
 *
 * <p>The tool is declared on the Messages request as
 * {@code {"type":"memory_20250818","name":"memory"}} (no beta header — the
 * declaration lives in the GA namespace); the model then issues ordinary
 * {@code tool_use} blocks named {@code memory} whose input carries one of
 * six typed commands, each translated here onto the Atmosphere store
 * (wire field names verified against the Anthropic Java SDK types
 * {@code BetaMemoryTool20250818*Command}):</p>
 * <ul>
 *   <li>{@code view} ({@code path}, optional {@code view_range}) —
 *       {@link AgentFileSystem#ls} for directories,
 *       {@link AgentFileSystem#read} (line-numbered) for files</li>
 *   <li>{@code create} ({@code path}, {@code file_text}) —
 *       {@link AgentFileSystem#write}</li>
 *   <li>{@code str_replace} ({@code path}, {@code old_str}, {@code new_str})
 *       — {@link AgentFileSystem#edit} (unique-match semantics)</li>
 *   <li>{@code insert} ({@code path}, {@code insert_line},
 *       {@code insert_text}) — read + line splice + bounded write</li>
 *   <li>{@code delete} ({@code path}) — {@link AgentFileSystem#delete}</li>
 *   <li>{@code rename} ({@code old_path}, {@code new_path}) —
 *       {@link AgentFileSystem#rename}</li>
 * </ul>
 *
 * <h2>Boundary safety</h2>
 * The model addresses a {@code /memories} tree; every path is required to
 * sit inside it before the prefix is stripped, and the store's own
 * traversal guards ({@code ..}, absolute paths, backslashes, empty
 * segments) plus {@link AgentFileSystem.Limits} bounds then apply to the
 * remainder. Rejections come back to the model as clear
 * {@code Error: ...} tool results — never a stack trace — so it can
 * correct course (Correctness Invariants #3 and #4). Like the built-in
 * file-tool floor, memory commands act only on the bounded
 * conversation-scoped store and are not routed through
 * {@code ToolApprovalPolicy} (there is no {@code ToolDefinition} to gate).
 *
 * <p>One documented edge: a directory left empty by {@code delete} is
 * indistinguishable from a missing path, so {@code view} on it reports
 * "File not found" rather than an empty listing.</p>
 */
final class AnthropicMemoryTool {

    /** Anthropic tool type discriminator on the Messages {@code tools} array. */
    static final String TOOL_TYPE = "memory_20250818";

    /** The tool name the model uses on its {@code tool_use} blocks. */
    static final String TOOL_NAME = "memory";

    /** The virtual root the model is trained to address. */
    static final String MEMORY_ROOT = "/memories";

    private static final Logger logger = LoggerFactory.getLogger(AnthropicMemoryTool.class);

    private AnthropicMemoryTool() {
    }

    /**
     * Resolve the conversation-scoped {@link AgentFileSystem} from the
     * session's injectables. The harness FILESYSTEM feature publishes the
     * scoped store directly ({@code AiEndpointHandler} at dispatch time);
     * the {@link AgentFileSystemProvider} fallback covers resource-free
     * paths, scoping by the same conversation identity the built-in file
     * tools use ({@link ToolScopes#conversationId}).
     *
     * @param session the live streaming session (may be {@code null})
     * @return the scoped store, or {@code null} when the feature is not
     *         active for this session — the caller then never declares the
     *         memory tool (Runtime Truth, Correctness Invariant #5)
     */
    static AgentFileSystem resolve(StreamingSession session) {
        if (session == null) {
            return null;
        }
        var injectables = session.injectables();
        if (injectables == null || injectables.isEmpty()) {
            return null;
        }
        if (injectables.get(AgentFileSystem.class) instanceof AgentFileSystem fs) {
            return fs;
        }
        if (injectables.get(AgentFileSystemProvider.class)
                instanceof AgentFileSystemProvider provider) {
            try {
                return provider.forConversation(ToolScopes.conversationId(injectables));
            } catch (RuntimeException e) {
                logger.warn("Anthropic memory tool not scoped for session {}: {}",
                        session.sessionId(), e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Execute one memory command against the store and format the tool
     * result for the model.
     *
     * @param fs   the conversation-scoped store (never {@code null})
     * @param args the parsed {@code tool_use} input
     * @return the tool result text — success detail or {@code Error: ...}
     */
    static String execute(AgentFileSystem fs, Map<String, Object> args) {
        var input = args != null ? args : Map.<String, Object>of();
        try {
            var command = str(input.get("command"));
            return switch (command) {
                case "view" -> view(fs, input);
                case "create" -> create(fs, input);
                case "str_replace" -> strReplace(fs, input);
                case "insert" -> insert(fs, input);
                case "delete" -> delete(fs, input);
                case "rename" -> rename(fs, input);
                default -> "Error: unsupported memory command '" + command + "'";
            };
        } catch (IllegalArgumentException | UncheckedIOException e) {
            // Traversal / bounds / not-found rejections surface as clear
            // tool-result strings so the model can correct course.
            return "Error: " + e.getMessage();
        }
    }

    // ---------- Commands ----------

    private static String view(AgentFileSystem fs, Map<String, Object> args) {
        var path = requiredPath(args, "path");
        var rel = toRelative(path);
        if (rel.isEmpty()) {
            return formatListing(fs.ls(null));
        }
        try {
            var entries = fs.ls(rel);
            if (!entries.isEmpty()) {
                return formatListing(entries);
            }
        } catch (IllegalArgumentException notADirectory) {
            // An existing file — fall through to the numbered read below.
        }
        return formatFile(fs.read(rel), viewRange(args));
    }

    private static String create(AgentFileSystem fs, Map<String, Object> args) {
        var path = requiredPath(args, "path");
        var rel = toRelativeEntry(path);
        fs.write(rel, str(args.get("file_text")));
        return "File created successfully at " + path;
    }

    private static String strReplace(AgentFileSystem fs, Map<String, Object> args) {
        var path = requiredPath(args, "path");
        var rel = toRelativeEntry(path);
        // Null/empty old_str and zero/multiple matches are rejected by the
        // store's unique-match edit semantics with clear messages.
        fs.edit(rel, nullableStr(args.get("old_str")), str(args.get("new_str")));
        return "File " + path + " has been edited";
    }

    private static String insert(AgentFileSystem fs, Map<String, Object> args) {
        var path = requiredPath(args, "path");
        var rel = toRelativeEntry(path);
        if (!(args.get("insert_line") instanceof Number lineNumber)) {
            throw new IllegalArgumentException("'insert_line' is required");
        }
        var line = lineNumber.intValue();
        var content = fs.read(rel);
        var trailingNewline = content.endsWith("\n");
        var lines = content.isEmpty()
                ? new ArrayList<String>()
                : new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (trailingNewline && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (line < 0 || line > lines.size()) {
            throw new IllegalArgumentException("insert_line " + line
                    + " is out of range 0.." + lines.size() + " for " + path);
        }
        var text = str(args.get("insert_text"));
        // The inserted text is its own line; strip one trailing newline so
        // the splice does not introduce a blank line.
        lines.add(line, text.endsWith("\n") ? text.substring(0, text.length() - 1) : text);
        // The bounded write re-checks Limits, so an over-limit insert is
        // rejected with the store's clear message.
        fs.write(rel, String.join("\n", lines) + (trailingNewline ? "\n" : ""));
        return "Text inserted at line " + line + " in " + path;
    }

    private static String delete(AgentFileSystem fs, Map<String, Object> args) {
        var path = requiredPath(args, "path");
        fs.delete(toRelativeEntry(path));
        return "Deleted " + path;
    }

    private static String rename(AgentFileSystem fs, Map<String, Object> args) {
        var oldPath = requiredPath(args, "old_path");
        var newPath = requiredPath(args, "new_path");
        fs.rename(toRelativeEntry(oldPath), toRelativeEntry(newPath));
        return "Renamed " + oldPath + " to " + newPath;
    }

    // ---------- Path mapping ----------

    /**
     * Map an Anthropic {@code /memories/...} path onto the store-relative
     * path the {@link AgentFileSystem} expects. {@code /memories} itself
     * maps to the empty root marker; anything outside the tree is rejected
     * at this boundary before the store's own guards run.
     */
    private static String toRelative(String path) {
        var trimmed = path.trim();
        if (trimmed.equals(MEMORY_ROOT) || trimmed.equals(MEMORY_ROOT + "/")) {
            return "";
        }
        if (!trimmed.startsWith(MEMORY_ROOT + "/")) {
            throw new IllegalArgumentException(
                    "path must be inside " + MEMORY_ROOT + ": " + path);
        }
        return trimmed.substring(MEMORY_ROOT.length() + 1);
    }

    /** {@link #toRelative} for commands that may not target the root itself. */
    private static String toRelativeEntry(String path) {
        var rel = toRelative(path);
        if (rel.isEmpty()) {
            throw new IllegalArgumentException(
                    "the " + MEMORY_ROOT + " root itself cannot be the target: " + path);
        }
        return rel;
    }

    // ---------- Formatting ----------

    private static String formatListing(List<AgentFileSystem.FileInfo> entries) {
        var out = new StringJoiner("\n");
        out.add("Directory: " + MEMORY_ROOT);
        for (var entry : entries) {
            out.add(entry.directory()
                    ? "- " + MEMORY_ROOT + "/" + entry.path() + "/"
                    : "- " + MEMORY_ROOT + "/" + entry.path()
                            + " (" + entry.size() + " bytes)");
        }
        return out.toString();
    }

    private static String formatFile(String content, int[] range) {
        var lines = content.isEmpty() ? new String[0] : content.split("\n", -1);
        var count = lines.length;
        // A trailing newline yields one empty trailing element — drop it so
        // the numbering matches what an editor shows.
        if (count > 0 && lines[count - 1].isEmpty() && content.endsWith("\n")) {
            count--;
        }
        var start = 1;
        var end = count;
        if (range != null) {
            start = Math.max(1, range[0]);
            end = range[1] == -1 ? count : Math.min(count, range[1]);
        }
        var out = new StringJoiner("\n");
        for (var i = start; i <= end; i++) {
            out.add(i + ": " + lines[i - 1]);
        }
        return out.toString();
    }

    // ---------- Argument helpers ----------

    private static int[] viewRange(Map<String, Object> args) {
        if (args.get("view_range") instanceof List<?> list && list.size() == 2
                && list.get(0) instanceof Number from && list.get(1) instanceof Number to) {
            return new int[]{from.intValue(), to.intValue()};
        }
        return null;
    }

    private static String requiredPath(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullableStr(Object value) {
        return value == null ? null : value.toString();
    }
}
