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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link AgentFileSystem} that routes each call to a delegate store by path
 * prefix, so one flat namespace the model sees can be split across several
 * bounded backends — e.g. a {@code memory/} prefix to a durable store while
 * everything else stays on the disk-backed {@link WorkspaceAgentFileSystem}
 * (deepagents-style composite backend routing).
 *
 * <h2>Routing key vs. delegate path</h2>
 * A route is a {@code prefix -> delegate} mapping. The prefix is only a
 * <em>routing key</em>: the path is passed <strong>unchanged</strong> to the
 * chosen delegate (the prefix is NOT stripped). Every delegate is itself a
 * bounded store rooted under one flat namespace, so {@code read},
 * {@code write}, {@code glob} and {@code grep} all agree on the same key
 * ({@code memory/notes.md} reads back exactly what {@code memory/notes.md}
 * wrote). A path routes to a prefix when it equals the prefix's path segments
 * or continues past them at a {@code /} boundary — {@code memory/notes.md}
 * and {@code memory} both route to {@code memory/}, but {@code memoryfoo}
 * does not. When several prefixes match, the <em>longest</em> (most specific)
 * one wins. A path matching no prefix routes to the default (fallback)
 * delegate.
 *
 * <h2>Whole-namespace operations</h2>
 * <ul>
 *   <li>{@link #ls(String)} of the root ({@code null}, blank or {@code "."})
 *       merges the root entries of every delegate (deduplicated by path) so
 *       the model sees the whole namespace; a non-root {@code dir} routes to
 *       the single delegate owning that subtree.</li>
 *   <li>{@link #glob(String)} fans out to every delegate and concatenates the
 *       matches across the whole namespace.</li>
 *   <li>{@link #grep(String, String)} of the root fans out to every delegate;
 *       a non-root {@code dir} routes to the single owning delegate.</li>
 * </ul>
 *
 * <h2>Correctness</h2>
 * Every delegate keeps its own {@link Limits} and traversal guards — the
 * composite never weakens them (a bounds violation on a delegate still
 * throws). The composite rejects a {@code null}/blank path at its own
 * boundary before routing (Correctness Invariant #4), and a
 * {@link #rename(String, String)} whose two ends route to different backends
 * fails loud with {@link IllegalArgumentException} rather than silently losing
 * data. Fan-out output stays bounded: each delegate caps its own {@code glob}
 * / {@code grep} results, and the number of delegates is fixed at construction
 * (not external input), so the concatenation is bounded (Correctness
 * Invariant #3).
 *
 * <p>This type is immutable and thread-safe: it holds an immutable route table
 * and delegates all locking to the underlying stores.</p>
 */
public final class CompositeAgentFileSystem implements AgentFileSystem {

    private final AgentFileSystem defaultFs;

    /** Routes sorted by key length descending, so the first match is the longest. */
    private final List<Route> routes;

    /**
     * Every distinct delegate (default first, then routes), deduplicated by
     * identity — the fixed fan-out target set for root {@code ls} / {@code glob}
     * / root {@code grep}.
     */
    private final List<AgentFileSystem> allDelegates;

    private CompositeAgentFileSystem(AgentFileSystem defaultFs, List<Route> unsortedRoutes) {
        if (defaultFs == null) {
            throw new IllegalArgumentException("default filesystem must not be null");
        }
        this.defaultFs = defaultFs;
        var sorted = new ArrayList<>(unsortedRoutes);
        // Longest key first => the first matching route is the most specific.
        sorted.sort(Comparator.comparingInt((Route r) -> r.key().length()).reversed()
                .thenComparing(Route::key));
        this.routes = List.copyOf(sorted);

        // Distinct delegates by identity, default first, then routes in the
        // (deterministic) sorted order — the fixed fan-out set.
        var seen = Collections.newSetFromMap(new IdentityHashMap<AgentFileSystem, Boolean>());
        var delegates = new ArrayList<AgentFileSystem>();
        seen.add(defaultFs);
        delegates.add(defaultFs);
        for (var route : this.routes) {
            if (seen.add(route.fs())) {
                delegates.add(route.fs());
            }
        }
        this.allDelegates = List.copyOf(delegates);
    }

    /**
     * Build a composite from a prefix-to-delegate map and a default delegate.
     *
     * @param routes    the {@code prefix -> delegate} routes (may be empty)
     * @param defaultFs the fallback delegate for unmatched paths (never
     *                  {@code null})
     * @return the composite filesystem
     * @throws IllegalArgumentException if {@code defaultFs} is {@code null}, a
     *                                  route prefix is blank or not a relative
     *                                  path, a route delegate is {@code null},
     *                                  or two prefixes collide
     */
    public static CompositeAgentFileSystem of(Map<String, AgentFileSystem> routes,
                                              AgentFileSystem defaultFs) {
        var builder = builder().defaultFs(defaultFs);
        if (routes != null) {
            for (var entry : routes.entrySet()) {
                builder.route(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    /** A fresh {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /** The fallback delegate for paths matching no route prefix. */
    public AgentFileSystem defaultFileSystem() {
        return defaultFs;
    }

    // ---------- single-path operations ----------

    @Override
    public List<FileInfo> ls(String dir) {
        if (isRoot(dir)) {
            return mergedRootListing();
        }
        return route(requirePath(dir)).ls(dir);
    }

    @Override
    public String read(String path) {
        return route(requirePath(path)).read(path);
    }

    @Override
    public void write(String path, String content) {
        route(requirePath(path)).write(path, content);
    }

    @Override
    public void edit(String path, String oldText, String newText) {
        route(requirePath(path)).edit(path, oldText, newText);
    }

    @Override
    public void delete(String path) {
        route(requirePath(path)).delete(path);
    }

    @Override
    public void rename(String oldPath, String newPath) {
        var from = route(requirePath(oldPath));
        var to = route(requirePath(newPath));
        if (from != to) {
            throw new IllegalArgumentException("cross-backend rename is not supported: "
                    + oldPath + " -> " + newPath
                    + " (source and destination route to different backends)");
        }
        from.rename(oldPath, newPath);
    }

    // ---------- whole-namespace operations ----------

    @Override
    public List<String> glob(String pattern) {
        // Fan out across the whole namespace and concatenate; each delegate
        // caps its own result, and the delegate count is fixed (Invariant #3).
        var matches = new ArrayList<String>();
        for (var delegate : allDelegates) {
            matches.addAll(delegate.glob(pattern));
        }
        return matches.stream().distinct().sorted().toList();
    }

    @Override
    public List<GrepHit> grep(String pattern, String dir) {
        if (isRoot(dir)) {
            var hits = new ArrayList<GrepHit>();
            for (var delegate : allDelegates) {
                hits.addAll(delegate.grep(pattern, dir));
            }
            return List.copyOf(hits);
        }
        return route(requirePath(dir)).grep(pattern, dir);
    }

    // ---------- helpers ----------

    /**
     * Merge the root listing of every delegate, deduplicated by path (first
     * delegate wins), directories first then files, each sorted by path — the
     * same ordering a single {@link WorkspaceAgentFileSystem} produces.
     */
    private List<FileInfo> mergedRootListing() {
        var byPath = new LinkedHashMap<String, FileInfo>();
        for (var delegate : allDelegates) {
            for (var info : delegate.ls(null)) {
                byPath.putIfAbsent(info.path(), info);
            }
        }
        return byPath.values().stream()
                .sorted(Comparator.comparing(FileInfo::directory).reversed()
                        .thenComparing(FileInfo::path))
                .toList();
    }

    /** Select the delegate for a path: longest matching prefix, else default. */
    private AgentFileSystem route(String path) {
        var probe = path.trim();
        for (var candidate : routes) {
            if (matches(probe, candidate.key())) {
                return candidate.fs();
            }
        }
        return defaultFs;
    }

    /**
     * A path routes to a prefix key when it equals the key or continues past
     * it at a {@code /} boundary — segment-aware so {@code memoryfoo} does not
     * match the {@code memory} key.
     */
    private static boolean matches(String path, String key) {
        return path.equals(key) || path.startsWith(key + "/");
    }

    private static boolean isRoot(String dir) {
        return dir == null || dir.isBlank() || ".".equals(dir.trim());
    }

    /**
     * The composite's own boundary guard: reject a {@code null}/blank path
     * before routing (Correctness Invariant #4). The chosen delegate performs
     * the full traversal validation.
     */
    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return path;
    }

    /** One {@code prefix -> delegate} route; {@code key} is the segment-normalized prefix. */
    private record Route(String key, AgentFileSystem fs) {
    }

    /**
     * Fluent builder for a {@link CompositeAgentFileSystem}. Not thread-safe;
     * build once at wiring time.
     */
    public static final class Builder {

        private AgentFileSystem defaultFs;
        private final Map<String, AgentFileSystem> routes = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Set the fallback delegate for paths matching no route prefix.
         *
         * @param fs the default delegate (never {@code null})
         * @return this builder
         */
        public Builder defaultFs(AgentFileSystem fs) {
            if (fs == null) {
                throw new IllegalArgumentException("default filesystem must not be null");
            }
            this.defaultFs = fs;
            return this;
        }

        /**
         * Route every path under {@code prefix} to {@code fs}. A trailing
         * {@code /} on the prefix is optional ({@code "memory/"} and
         * {@code "memory"} are equivalent).
         *
         * @param prefix the routing-key prefix (a relative, non-blank path)
         * @param fs     the delegate for that subtree (never {@code null})
         * @return this builder
         * @throws IllegalArgumentException if the prefix is blank, not a
         *                                  relative path, or already registered
         */
        public Builder route(String prefix, AgentFileSystem fs) {
            if (fs == null) {
                throw new IllegalArgumentException("route filesystem must not be null for prefix: "
                        + prefix);
            }
            var key = routeKey(prefix);
            if (routes.putIfAbsent(key, fs) != null) {
                throw new IllegalArgumentException("duplicate route prefix: " + prefix
                        + " (normalizes to '" + key + "')");
            }
            return this;
        }

        /** Build the immutable composite. */
        public CompositeAgentFileSystem build() {
            var routeList = new ArrayList<Route>(routes.size());
            for (var entry : routes.entrySet()) {
                routeList.add(new Route(entry.getKey(), entry.getValue()));
            }
            return new CompositeAgentFileSystem(defaultFs, routeList);
        }

        /**
         * Normalize and validate a route prefix into its segment key: strip a
         * single trailing {@code /}, then reject the same shapes the delegate
         * stores reject (blank, backslash, absolute, {@code .} / {@code ..} /
         * empty segments) so a misconfigured route fails loud at construction
         * (Correctness Invariant #4).
         */
        private static String routeKey(String prefix) {
            if (prefix == null || prefix.isBlank()) {
                throw new IllegalArgumentException("route prefix must not be blank");
            }
            var trimmed = prefix.trim();
            if (trimmed.contains("\\")) {
                throw new IllegalArgumentException("route prefix must use forward slashes: "
                        + prefix);
            }
            var key = trimmed.endsWith("/")
                    ? trimmed.substring(0, trimmed.length() - 1)
                    : trimmed;
            if (key.isEmpty() || key.startsWith("/") || Path.of(key).isAbsolute()) {
                throw new IllegalArgumentException(
                        "route prefix must be a relative, non-root path: " + prefix);
            }
            for (var segment : key.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new IllegalArgumentException(
                            "route prefix has an illegal segment ('" + segment + "'): " + prefix);
                }
            }
            return key;
        }
    }
}
