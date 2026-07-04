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

import java.util.List;

/**
 * The agent-facing virtual filesystem: a bounded, conversation-scoped file
 * store the model reads and writes through tools ({@code ls} /
 * {@code read_file} / {@code write_file} / {@code edit_file} / {@code glob} /
 * {@code grep}). All paths are relative to the store's root — implementations
 * MUST reject traversal ({@code ..}, absolute paths, backslashes, empty
 * segments) and enforce {@link Limits} on every write (Correctness
 * Invariants #3 and #4).
 *
 * <p>The default implementation is {@link WorkspaceAgentFileSystem}, rooted
 * at {@code files/{conversationId}/} under the agent workspace root. Native
 * filesystem bridges (Spring AI Alibaba {@code FilesystemBackend}, ADK
 * {@code BaseArtifactService}, Anthropic {@code memory_20250818}) expose this
 * same store through a framework's native tool surface.</p>
 */
public interface AgentFileSystem {

    /**
     * Metadata for one directory entry.
     *
     * @param path      the entry's path relative to the store root
     * @param size      the file size in bytes ({@code 0} for directories)
     * @param directory whether the entry is a directory
     */
    record FileInfo(String path, long size, boolean directory) {
    }

    /**
     * One matching line from {@link #grep}.
     *
     * @param path the file's path relative to the store root
     * @param line the 1-based line number of the match
     * @param text the matching line's text
     */
    record GrepHit(String path, int line, String text) {
    }

    /**
     * Hard bounds enforced on every write (Correctness Invariant #3 — a
     * model-writable store without bounds is a DoS vector). Over-limit
     * operations are rejected with a clear {@link IllegalArgumentException}.
     *
     * @param maxFileBytes  maximum size of one file, in bytes
     * @param maxFiles      maximum number of files in the store
     * @param maxTotalBytes maximum cumulative size of all files, in bytes
     */
    record Limits(int maxFileBytes, int maxFiles, long maxTotalBytes) {

        /** Default per-file cap: 512 KiB. */
        public static final int DEFAULT_MAX_FILE_BYTES = 512 * 1024;

        /** Default file-count cap: 256 files. */
        public static final int DEFAULT_MAX_FILES = 256;

        /** Default total-bytes cap: 16 MiB. */
        public static final long DEFAULT_MAX_TOTAL_BYTES = 16L * 1024 * 1024;

        public Limits {
            if (maxFileBytes <= 0 || maxFiles <= 0 || maxTotalBytes <= 0) {
                throw new IllegalArgumentException("limits must be positive: "
                        + maxFileBytes + "/" + maxFiles + "/" + maxTotalBytes);
            }
        }

        /** The default bounds: 512 KiB per file, 256 files, 16 MiB total. */
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_FILES,
                    DEFAULT_MAX_TOTAL_BYTES);
        }
    }

    /**
     * List the entries directly under a directory.
     *
     * @param dir the directory relative to the root; {@code null}, blank or
     *            {@code "."} list the root itself
     * @return the entries, directories first then files, each sorted by path
     * @throws IllegalArgumentException on traversal or a non-directory path
     */
    List<FileInfo> ls(String dir);

    /**
     * Read a file's full content as UTF-8 text.
     *
     * @param path the file relative to the root
     * @return the content, never {@code null}
     * @throws IllegalArgumentException on traversal, a missing file, or a
     *                                  file larger than {@code maxFileBytes}
     */
    String read(String path);

    /**
     * Create or replace a file with the given UTF-8 content, creating parent
     * directories as needed. Rejected when the content exceeds
     * {@code maxFileBytes}, the store already holds {@code maxFiles} files
     * (for a new path), or the write would push the store past
     * {@code maxTotalBytes}.
     *
     * @param path    the file relative to the root
     * @param content the new content (never {@code null})
     * @throws IllegalArgumentException on traversal or a bounds violation
     */
    void write(String path, String content);

    /**
     * Replace exactly one occurrence of {@code oldText} with {@code newText}
     * in the file. Zero matches and multiple matches are both errors — the
     * caller must disambiguate with more context (Claude-Code edit semantics).
     *
     * @param path    the file relative to the root
     * @param oldText the text to find — must occur exactly once
     * @param newText the replacement text
     * @throws IllegalArgumentException on traversal, a missing file, zero or
     *                                  multiple matches, or a bounds violation
     */
    void edit(String path, String oldText, String newText);

    /**
     * Find files whose root-relative path matches a glob pattern (e.g.
     * {@code **&#47;*.md}).
     *
     * @param pattern the glob pattern
     * @return the matching file paths, sorted
     * @throws IllegalArgumentException on an invalid pattern
     */
    List<String> glob(String pattern);

    /**
     * Search file contents for a regular expression.
     *
     * @param pattern the regex to search for
     * @param dir     the directory to search under, relative to the root;
     *                {@code null}, blank or {@code "."} search everything
     * @return the matching lines (output is capped — implementations bound
     *         the hit count)
     * @throws IllegalArgumentException on traversal or an invalid pattern
     */
    List<GrepHit> grep(String pattern, String dir);

    /**
     * Delete a file, or a directory together with everything under it.
     * Consumed by the native filesystem bridges (e.g. the Anthropic
     * {@code memory_20250818} {@code delete} command) — the built-in tool
     * floor deliberately does not expose it. Deletion only shrinks the
     * store, so no {@link Limits} check applies; the traversal guards do.
     *
     * @param path the file or directory relative to the root
     * @throws IllegalArgumentException on traversal or a missing path
     */
    void delete(String path);

    /**
     * Rename (move) a file or directory. The destination must not already
     * exist — callers that intend replacement delete it first. Parent
     * directories of the destination are created as needed. A rename keeps
     * the store's file count and byte total unchanged, so no {@link Limits}
     * check applies beyond the traversal guards on both paths.
     *
     * @param oldPath the existing file or directory relative to the root
     * @param newPath the destination relative to the root
     * @throws IllegalArgumentException on traversal, a missing source, an
     *                                  existing destination, or a destination
     *                                  inside the directory being moved
     */
    void rename(String oldPath, String newPath);
}
