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
package org.atmosphere.ai.state;

import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed default implementation of {@link AgentState}. Reads and writes
 * plain Markdown and JSONL under an OpenClaw-compatible workspace root.
 *
 * <h2>On-disk layout</h2>
 *
 * <pre>
 * {workspaceRoot}/
 *   AGENTS.md                           ← operating rules
 *   SOUL.md                             ← persona
 *   USER.md                             ← user profile
 *   IDENTITY.md                         ← agent identity
 *   MEMORY.md                           ← durable facts (one per line)
 *   memory/YYYY-MM-DD.md                ← daily notes (one per line)
 *   agents/{agentId}/sessions/{sessionId}.jsonl   ← conversation transcripts
 * </pre>
 *
 * <h2>Boundary safety</h2>
 *
 * All filesystem paths derived from {@code userId}, {@code agentId}, or
 * {@code sessionId} are validated at the boundary: reject empty segments,
 * path separators, {@code ..}, and anything that resolves outside the
 * configured workspace root. This is enforced by {@link #resolveSafe(Path, String)}.
 *
 * <h2>Thread safety</h2>
 *
 * Per-session locks serialize writes to the same session JSONL file.
 * {@code MEMORY.md} and {@code memory/YYYY-MM-DD.md} writes are serialized by
 * per-file locks. Reads do not lock.
 */
public class FileSystemAgentState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemAgentState.class);

    private final Path workspaceRoot;
    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> workingMemory = new ConcurrentHashMap<>();

    /**
     * Create a new file-backed state rooted at the given directory. The
     * directory is created if it does not exist. The default layout files
     * are NOT seeded here — that is the job of
     * {@code AgentWorkspace} (primitive 2).
     *
     * @param workspaceRoot absolute path to the agent workspace
     */
    public FileSystemAgentState(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.workspaceRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create workspace root: " + this.workspaceRoot, e);
        }
    }

    // ---------- Conversation ----------

    @Override
    public List<ChatMessage> getConversation(String agentId, String sessionId) {
        var path = sessionJsonl(agentId, sessionId);
        if (!Files.exists(path)) {
            return List.of();
        }
        var messages = new ArrayList<ChatMessage>();
        try {
            for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                var msg = JsonlCodec.parseChatMessage(line);
                msg.ifPresent(messages::add);
            }
        } catch (IOException e) {
            logger.warn("Failed to read conversation {}: {}", path, e.getMessage());
            return List.of();
        }
        return List.copyOf(messages);
    }

    @Override
    public void appendConversation(String agentId, String sessionId, ChatMessage message) {
        var path = sessionJsonl(agentId, sessionId);
        withLock(path, () -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path,
                        JsonlCodec.encodeChatMessage(message) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warn("Failed to append conversation {}: {}", path, e.getMessage());
            }
        });
    }

    @Override
    public void clearConversation(String agentId, String sessionId) {
        var path = sessionJsonl(agentId, sessionId);
        withLock(path, () -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.warn("Failed to clear conversation {}: {}", path, e.getMessage());
            }
        });
    }

    // ---------- Facts ----------

    @Override
    public List<MemoryEntry> getFacts(String userId, String agentId) {
        return readEntries(memoryMdPath());
    }

    @Override
    public MemoryEntry addFact(String userId, String agentId, String content) {
        var entry = new MemoryEntry(UUID.randomUUID().toString(), content, Instant.now());
        appendEntry(memoryMdPath(), entry);
        return entry;
    }

    @Override
    public void removeFact(String userId, String agentId, String factId) {
        rewriteWithout(memoryMdPath(), factId);
    }

    // ---------- Daily notes ----------

    @Override
    public List<MemoryEntry> getDailyNotes(String userId, String agentId, LocalDate date) {
        return readEntries(dailyNotePath(date));
    }

    @Override
    public MemoryEntry addDailyNote(String userId, String agentId, String content) {
        var entry = new MemoryEntry(UUID.randomUUID().toString(), content, Instant.now());
        appendEntry(dailyNotePath(LocalDate.now()), entry);
        return entry;
    }

    // ---------- Working memory ----------

    @Override
    public Optional<String> getWorkingMemory(String sessionId, String key) {
        var session = workingMemory.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.get(key));
    }

    @Override
    public void setWorkingMemory(String sessionId, String key, String value) {
        workingMemory.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void clearWorkingMemory(String sessionId) {
        workingMemory.remove(sessionId);
    }

    // ---------- Rules ----------

    @Override
    public RuleSet getRules(String userId, String agentId) {
        var identity = readFile(workspaceRoot.resolve("IDENTITY.md"));
        var persona = readFile(workspaceRoot.resolve("SOUL.md"));
        var userProfile = readFile(workspaceRoot.resolve("USER.md"));
        var rules = readFile(workspaceRoot.resolve("AGENTS.md"));

        var composed = new StringBuilder();
        appendSection(composed, "Identity", identity);
        appendSection(composed, "Persona", persona);
        appendSection(composed, "User", userProfile);
        appendSection(composed, "Operating rules", rules);

        return new RuleSet(composed.toString().trim(), identity, persona, userProfile, rules);
    }

    // ---------- Workspace ----------

    @Override
    public Optional<Path> workspaceRoot(String agentId) {
        return Optional.of(workspaceRoot);
    }

    // ---------- Helpers ----------

    private Path memoryMdPath() {
        return workspaceRoot.resolve("MEMORY.md");
    }

    private Path dailyNotePath(LocalDate date) {
        return workspaceRoot.resolve("memory").resolve(date + ".md");
    }

    private Path sessionJsonl(String agentId, String sessionId) {
        var safeAgent = validateSegment("agentId", agentId);
        var safeSession = validateSegment("sessionId", sessionId);
        return resolveSafe(
                workspaceRoot.resolve("agents").resolve(safeAgent).resolve("sessions"),
                safeSession + ".jsonl");
    }

    private String validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(label + " contains illegal path characters: " + value);
        }
        return value;
    }

    private Path resolveSafe(Path parent, String segment) {
        var resolved = parent.resolve(segment).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path escapes workspace: " + resolved);
        }
        return resolved;
    }

    private void withLock(Path path, Runnable body) {
        var lock = locks.computeIfAbsent(path, k -> new ReentrantLock());
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    private List<MemoryEntry> readEntries(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(MarkdownEntryCodec::parse)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(MemoryEntry::createdAt))
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to read entries from {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private void appendEntry(Path path, MemoryEntry entry) {
        withLock(path, () -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path,
                        MarkdownEntryCodec.format(entry) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warn("Failed to append entry {}: {}", path, e.getMessage());
            }
        });
    }

    private void rewriteWithout(Path path, String factId) {
        withLock(path, () -> {
            if (!Files.exists(path)) {
                return;
            }
            try {
                var kept = new ArrayList<String>();
                for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    var parsed = MarkdownEntryCodec.parse(line);
                    if (parsed.isEmpty() || !parsed.get().id().equals(factId)) {
                        kept.add(line);
                    }
                }
                Files.write(path, kept, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warn("Failed to rewrite entries in {}: {}", path, e.getMessage());
            }
        });
    }

    private String readFile(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read file {}: {}", path, e.getMessage());
            return "";
        }
    }

    private static void appendSection(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("## ").append(title).append("\n\n").append(body.trim());
    }

    // ---------- Codec for Markdown entries ----------
    // MEMORY.md and memory/YYYY-MM-DD.md use a simple append-only line format:
    //   - [id:<uuid>|at:<ISO-8601>] <content>
    // Lines without this prefix are preserved on rewrite but skipped on parse
    // (so hand-edited notes coexist with machine-written ones).

    static final class MarkdownEntryCodec {
        private static final String PREFIX = "- [id:";

        static String format(MemoryEntry entry) {
            return PREFIX + entry.id() + "|at:" + entry.createdAt() + "] " + entry.content();
        }

        static Optional<MemoryEntry> parse(String line) {
            if (line == null || !line.startsWith(PREFIX)) {
                return Optional.empty();
            }
            var close = line.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            var meta = line.substring(PREFIX.length(), close);
            var rest = line.substring(close + 1).trim();
            var parts = meta.split("\\|at:", 2);
            if (parts.length != 2) {
                return Optional.empty();
            }
            try {
                return Optional.of(new MemoryEntry(parts[0], rest, Instant.parse(parts[1])));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }

    // ---------- Codec for conversation JSONL ----------
    // Session files store one JSON object per line:
    //   {"role":"user","content":"..."[,"toolCallId":"..."]}
    // Hand-rolled to avoid a JSON library dependency at this layer.

    static final class JsonlCodec {
        static String encodeChatMessage(ChatMessage message) {
            var sb = new StringBuilder("{\"role\":\"")
                    .append(escape(message.role()))
                    .append("\",\"content\":\"")
                    .append(escape(message.content()))
                    .append('"');
            if (message.toolCallId() != null) {
                sb.append(",\"toolCallId\":\"").append(escape(message.toolCallId())).append('"');
            }
            sb.append('}');
            return sb.toString();
        }

        static Optional<ChatMessage> parseChatMessage(String line) {
            if (line == null || line.isBlank()) {
                return Optional.empty();
            }
            var fields = extractFields(line);
            var role = fields.get("role");
            var content = fields.get("content");
            if (role == null || content == null) {
                return Optional.empty();
            }
            return Optional.of(new ChatMessage(role, content, fields.get("toolCallId")));
        }

        private static Map<String, String> extractFields(String line) {
            var out = new HashMap<String, String>();
            var i = 0;
            var n = line.length();
            while (i < n) {
                var qStart = line.indexOf('"', i);
                if (qStart < 0) {
                    break;
                }
                var qEnd = findCloseQuote(line, qStart + 1);
                if (qEnd < 0) {
                    break;
                }
                var key = unescape(line.substring(qStart + 1, qEnd));
                var colon = line.indexOf(':', qEnd + 1);
                if (colon < 0) {
                    break;
                }
                var vStart = line.indexOf('"', colon + 1);
                if (vStart < 0) {
                    break;
                }
                var vEnd = findCloseQuote(line, vStart + 1);
                if (vEnd < 0) {
                    break;
                }
                out.put(key, unescape(line.substring(vStart + 1, vEnd)));
                i = vEnd + 1;
            }
            return out;
        }

        private static int findCloseQuote(String s, int from) {
            var escaped = false;
            for (var i = from; i < s.length(); i++) {
                var c = s.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
            }
            return -1;
        }

        private static String escape(String value) {
            if (value == null) {
                return "";
            }
            var sb = new StringBuilder(value.length());
            for (var i = 0; i < value.length(); i++) {
                var c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            return sb.toString();
        }

        private static String unescape(String value) {
            var sb = new StringBuilder(value.length());
            var escaped = false;
            for (var i = 0; i < value.length(); i++) {
                var c = value.charAt(i);
                if (escaped) {
                    switch (c) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default -> {
                            sb.append('\\');
                            sb.append(c);
                        }
                    }
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        }
    }
}
