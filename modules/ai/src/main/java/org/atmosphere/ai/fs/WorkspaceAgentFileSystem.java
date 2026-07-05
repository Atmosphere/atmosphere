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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Default {@link AgentFileSystem}: a directory-rooted store under the agent
 * workspace substrate, conventionally {@code {agentRoot}/files/{conversationId}/}
 * (see {@link #forConversation}).
 *
 * <h2>Boundary safety</h2>
 * Every path from the model is validated at the boundary before any I/O:
 * absolute paths, backslashes, empty segments, {@code .} and {@code ..}
 * segments are all rejected, and the resolved path is normalized and
 * containment-checked against the root — the same guard pattern as
 * {@code FileSystemAgentState.resolveSafe} (Correctness Invariant #4).
 *
 * <h2>Bounds</h2>
 * {@link Limits} are enforced on every mutation with clear rejection
 * messages (Correctness Invariant #3): per-file byte cap, store file-count
 * cap, and store total-byte cap. {@link #grep} output is capped at
 * {@value #MAX_GREP_HITS} hits.
 *
 * <h2>Thread safety</h2>
 * All mutations serialize on one store-wide lock — strictly stronger than
 * per-path locking, and required so the file-count / total-bytes accounting
 * that gates each write cannot race a concurrent write to a sibling path.
 * Reads do not lock.
 */
public final class WorkspaceAgentFileSystem implements AgentFileSystem {

    /** Hard cap on {@link #grep} results so tool output stays bounded. */
    public static final int MAX_GREP_HITS = 500;

    /** Hard cap on one {@link #grep} hit's line text, in chars. */
    public static final int MAX_GREP_LINE_CHARS = 500;

    /** Hard cap on a {@link #grep} regex pattern's length, in chars. */
    public static final int MAX_GREP_PATTERN_CHARS = 512;

    /**
     * Wall-clock budget for one {@link #grep} call. The pattern is
     * model-supplied and {@code java.util.regex} backtracking is not
     * interruptible, so without a deadline a catastrophic pattern pins a
     * carrier thread forever (Correctness Invariant #3).
     */
    static final long GREP_DEADLINE_NANOS = 2_000_000_000L;

    private static final Logger logger =
            LoggerFactory.getLogger(WorkspaceAgentFileSystem.class);

    /**
     * Canonical live instance per root. The bounds accounting is only sound
     * while every writer to one root shares one {@link #writeLock}; the
     * provider's LRU cache can evict a conversation whose instance is still
     * held by an in-flight request, and a naive rebuild would introduce a
     * second lock over the same directory. Weak values: an entry lives
     * exactly as long as someone still references the instance — the precise
     * condition under which a second instance would be unsafe.
     */
    private static final ConcurrentHashMap<Path, WeakReference<WorkspaceAgentFileSystem>>
            LIVE = new ConcurrentHashMap<>();

    private final Path root;
    private final Limits limits;
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Create a store rooted at the given directory (created if absent).
     *
     * @param root   the store root
     * @param limits the hard bounds (never {@code null})
     */
    public WorkspaceAgentFileSystem(Path root, Limits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
        this.limits = limits;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create filesystem root: " + this.root, e);
        }
    }

    /**
     * Create the conversation-scoped store at
     * {@code {agentRoot}/files/{conversationId}/}. The conversation id is
     * validated as a single path segment.
     *
     * @param agentRoot      the agent's workspace root
     * @param conversationId the conversation scope
     * @param limits         the hard bounds
     * @return the conversation-scoped store
     */
    public static WorkspaceAgentFileSystem forConversation(Path agentRoot,
                                                           String conversationId,
                                                           Limits limits) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (conversationId.contains("/") || conversationId.contains("\\")
                || conversationId.contains("..")) {
            throw new IllegalArgumentException(
                    "conversationId contains illegal path characters: " + conversationId);
        }
        var conversationsRoot = agentRoot.resolve("files").toAbsolutePath().normalize();
        var storeRoot = conversationsRoot.resolve(conversationId).normalize();
        if (!Files.isDirectory(storeRoot)) {
            evictOldestConversations(conversationsRoot, limits.maxConversations());
        }
        // Canonical instance per root: while any caller still holds the
        // store, everyone else must share it (one write lock, one bounds
        // accounting). The weak value clears only when nobody does.
        LIVE.entrySet().removeIf(e -> e.getValue().get() == null);
        while (true) {
            var ref = LIVE.computeIfAbsent(storeRoot,
                    k -> new WeakReference<>(new WorkspaceAgentFileSystem(k, limits)));
            var live = ref.get();
            if (live != null) {
                return live;
            }
            LIVE.remove(storeRoot, ref);
        }
    }

    /**
     * Keep the on-disk conversation-store count under the cap before a new
     * store directory is created: delete the least-recently-modified
     * conversation subtrees, skipping any store that is still live (a held
     * instance whose directory vanished underneath it would turn every
     * subsequent write into an error). Conversation ids arrive from external
     * input — without this sweep, disk use is unbounded (Invariant #3).
     */
    private static void evictOldestConversations(Path conversationsRoot, int maxConversations) {
        if (!Files.isDirectory(conversationsRoot)) {
            return;
        }
        List<Path> conversations;
        try (Stream<Path> entries = Files.list(conversationsRoot)) {
            conversations = entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(WorkspaceAgentFileSystem::lastModified))
                    .toList();
        } catch (IOException e) {
            logger.warn("Could not scan conversation stores under {}: {}",
                    conversationsRoot, e.getMessage());
            return;
        }
        var excess = conversations.size() - (maxConversations - 1);
        for (var conversation : conversations) {
            if (excess <= 0) {
                break;
            }
            if (LIVE.containsKey(conversation.toAbsolutePath().normalize())) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(conversation)) {
                for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
                logger.info("Evicted conversation store {} (over the {}-conversation cap)",
                        conversation, maxConversations);
                excess--;
            } catch (IOException e) {
                logger.warn("Could not evict conversation store {}: {}",
                        conversation, e.getMessage());
            }
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The store's root directory (test / bridge use). */
    public Path root() {
        return root;
    }

    /** The store's hard bounds. */
    public Limits limits() {
        return limits;
    }

    @Override
    public List<FileInfo> ls(String dir) {
        var target = resolveDir(dir);
        if (!Files.exists(target)) {
            return List.of();
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("Not a directory: " + relativize(target));
        }
        try (Stream<Path> entries = Files.list(target)) {
            return entries
                    .map(p -> new FileInfo(relativize(p), fileSize(p), Files.isDirectory(p)))
                    .sorted(Comparator.comparing(FileInfo::directory).reversed()
                            .thenComparing(FileInfo::path))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + relativize(target), e);
        }
    }

    @Override
    public String read(String path) {
        var file = resolveSafe(validatePath(path));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        var size = fileSize(file);
        if (size > limits.maxFileBytes()) {
            throw new IllegalArgumentException("File too large to read: " + path
                    + " is " + size + " bytes (limit " + limits.maxFileBytes() + ")");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    @Override
    public void write(String path, String content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        var file = resolveSafe(validatePath(path));
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        writeLock.lock();
        try {
            enforceWriteBounds(path, file, bytes.length);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void edit(String path, String oldText, String newText) {
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty");
        }
        var replacement = newText == null ? "" : newText;
        var file = resolveSafe(validatePath(path));
        writeLock.lock();
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("File not found: " + path);
            }
            var content = Files.readString(file, StandardCharsets.UTF_8);
            var matches = countOccurrences(content, oldText);
            if (matches == 0) {
                throw new IllegalArgumentException("oldText not found in " + path
                        + " — nothing was changed");
            }
            if (matches > 1) {
                throw new IllegalArgumentException("oldText matches " + matches
                        + " times in " + path
                        + " — it must match exactly once; include more surrounding context");
            }
            var index = content.indexOf(oldText);
            var edited = content.substring(0, index) + replacement
                    + content.substring(index + oldText.length());
            var bytes = edited.getBytes(StandardCharsets.UTF_8);
            enforceWriteBounds(path, file, bytes.length);
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to edit " + path, e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<String> glob(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        java.nio.file.PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException e) {
            // PatternSyntaxException / IllegalArgumentException for malformed globs.
            throw new IllegalArgumentException("Invalid glob pattern: " + pattern, e);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(matcher::matches)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to glob " + pattern, e);
        }
    }

    @Override
    public List<GrepHit> grep(String pattern, String dir) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        if (pattern.length() > MAX_GREP_PATTERN_CHARS) {
            throw new IllegalArgumentException("Pattern too long: " + pattern.length()
                    + " chars (limit " + MAX_GREP_PATTERN_CHARS + ")");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + pattern, e);
        }
        var target = resolveDir(dir);
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        var deadline = System.nanoTime() + GREP_DEADLINE_NANOS;
        var hits = new ArrayList<GrepHit>();
        try (Stream<Path> walk = Files.walk(target)) {
            for (var file : walk.filter(p -> Files.isRegularFile(p)
                    && !Files.isSymbolicLink(p)).sorted().toList()) {
                grepFile(file, regex, hits, deadline);
                if (hits.size() >= MAX_GREP_HITS) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to grep " + pattern, e);
        } catch (GrepDeadlineExceeded e) {
            throw new IllegalArgumentException("grep timed out on pattern '" + pattern
                    + "' — simplify the pattern (catastrophic backtracking is cut off after "
                    + GREP_DEADLINE_NANOS / 1_000_000 + " ms)");
        }
        return List.copyOf(hits);
    }

    @Override
    public void delete(String path) {
        var target = resolveSafe(validatePath(path));
        writeLock.lock();
        try {
            if (!Files.exists(target)) {
                throw new IllegalArgumentException("Not found: " + path);
            }
            if (Files.isDirectory(target)) {
                // Children before parents — reverse-sorted walk.
                try (Stream<Path> walk = Files.walk(target)) {
                    for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            } else {
                Files.delete(target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void rename(String oldPath, String newPath) {
        var source = resolveSafe(validatePath(oldPath));
        var target = resolveSafe(validatePath(newPath));
        writeLock.lock();
        try {
            if (!Files.exists(source)) {
                throw new IllegalArgumentException("Not found: " + oldPath);
            }
            if (Files.exists(target)) {
                throw new IllegalArgumentException("Destination already exists: " + newPath
                        + " — delete it first if you want to replace it");
            }
            if (Files.isDirectory(source) && target.startsWith(source)) {
                throw new IllegalArgumentException("Cannot move " + oldPath
                        + " into itself (" + newPath + ")");
            }
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to rename " + oldPath + " to " + newPath, e);
        } finally {
            writeLock.unlock();
        }
    }

    // ---------- Helpers ----------

    private void grepFile(Path file, Pattern regex, List<GrepHit> hits, long deadline) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Binary or unreadable content is skipped, not fatal — grep is a
            // best-effort read surface.
            return;
        }
        var relative = relativize(file);
        for (int i = 0; i < lines.size() && hits.size() < MAX_GREP_HITS; i++) {
            // The matcher sees a deadline-checking view of the line: regex
            // backtracking is not interruptible, and both the pattern and the
            // file content are model-supplied, so a catastrophic pattern must
            // be cut off by charAt itself (Invariant #3).
            if (regex.matcher(new DeadlineCharSequence(lines.get(i), deadline)).find()) {
                var text = lines.get(i);
                if (text.length() > MAX_GREP_LINE_CHARS) {
                    text = text.substring(0, MAX_GREP_LINE_CHARS) + "…";
                }
                hits.add(new GrepHit(relative, i + 1, text));
            }
        }
    }

    /** Thrown by {@link DeadlineCharSequence} when the grep budget is spent. */
    private static final class GrepDeadlineExceeded extends RuntimeException {
        GrepDeadlineExceeded() {
            super(null, null, false, false);
        }
    }

    /**
     * A char view that enforces the grep deadline from inside the regex
     * engine: {@code java.util.regex} polls neither interrupts nor time, so
     * the only way to bound a backtracking blowup is to make every
     * {@code charAt} access check the clock (amortized — every 2048 reads).
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String value;
        private final long deadline;
        private int reads;

        DeadlineCharSequence(String value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x7FF) == 0 && System.nanoTime() > deadline) {
                throw new GrepDeadlineExceeded();
            }
            return value.charAt(index);
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(value.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Enforce the three {@link Limits} for a pending write of
     * {@code newSize} bytes to {@code file}. Caller holds the write lock.
     */
    private void enforceWriteBounds(String path, Path file, int newSize) throws IOException {
        if (newSize > limits.maxFileBytes()) {
            throw new IllegalArgumentException("Write rejected: " + path + " would be "
                    + newSize + " bytes, over the " + limits.maxFileBytes()
                    + "-byte per-file limit");
        }
        var existing = Files.isRegularFile(file) ? fileSize(file) : -1L;
        long count = 0;
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (var p : walk.filter(Files::isRegularFile).toList()) {
                count++;
                total += fileSize(p);
            }
        }
        if (existing < 0 && count >= limits.maxFiles()) {
            throw new IllegalArgumentException("Write rejected: the store already holds "
                    + count + " files, at the " + limits.maxFiles() + "-file limit");
        }
        var projected = total - Math.max(existing, 0) + newSize;
        if (projected > limits.maxTotalBytes()) {
            throw new IllegalArgumentException("Write rejected: " + path + " would bring the "
                    + "store to " + projected + " bytes, over the " + limits.maxTotalBytes()
                    + "-byte total limit");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        var index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private Path resolveDir(String dir) {
        if (dir == null || dir.isBlank() || ".".equals(dir.trim())) {
            return root;
        }
        return resolveSafe(validatePath(dir));
    }

    /**
     * Validate a model-supplied relative path: reject absolute paths,
     * backslashes, blank input, and empty / {@code .} / {@code ..} segments.
     * Own copy of the {@code FileSystemAgentState} segment guards, extended
     * to multi-segment relative paths.
     *
     * @return the validated path, trimmed
     */
    private static String validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        var trimmed = path.trim();
        if (trimmed.contains("\\")) {
            throw new IllegalArgumentException("path must use forward slashes: " + path);
        }
        if (trimmed.startsWith("/") || Path.of(trimmed).isAbsolute()) {
            throw new IllegalArgumentException("path must be relative: " + path);
        }
        for (var segment : trimmed.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "path contains an illegal segment ('" + segment + "'): " + path);
            }
        }
        return trimmed;
    }

    private Path resolveSafe(String validated) {
        var resolved = root.resolve(validated).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the store root: " + validated);
        }
        // Defence in depth: the lexical check above cannot see symlinks. The
        // tools never create them, but an external process could place one in
        // the tree — re-check containment on the real path for anything that
        // already exists (Invariant #4).
        if (Files.exists(resolved)) {
            try {
                if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException(
                            "Path escapes the store root via a link: " + validated);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to resolve " + validated, e);
            }
        }
        return resolved;
    }

    private String relativize(Path path) {
        return root.relativize(path).toString();
    }

    private static long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }
}
