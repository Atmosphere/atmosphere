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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-agent factory for conversation-scoped {@link AgentFileSystem} views.
 * Registered into the endpoint's injectables at registration time — when the
 * conversation id is not yet known — and resolved to a concrete
 * {@link WorkspaceAgentFileSystem} rooted at {@code files/{conversationId}/}
 * at dispatch time ({@code AiEndpointHandler} publishes the scoped instance
 * under {@link AgentFileSystem} in the session's tool scope, and the built-in
 * file tools fall back to this provider on resource-free paths).
 *
 * <p>Instances are cached per conversation so concurrent tool calls within
 * one conversation share the same write lock and bounds accounting. The
 * cache is LRU-bounded at {@value #MAX_CACHED_CONVERSATIONS} entries —
 * conversation ids arrive from external input, and an unbounded map fed by
 * external input is a DoS vector (Correctness Invariant #3). Evicted
 * conversations lose nothing: their files stay on disk and a fresh view is
 * built on next access.</p>
 */
public final class AgentFileSystemProvider {

    private static final Logger logger = LoggerFactory.getLogger(AgentFileSystemProvider.class);

    /** Upper bound on cached conversation views. */
    public static final int MAX_CACHED_CONVERSATIONS = 256;

    /**
     * Comma-separated {@code prefix=dir} routes that compose durable, shared
     * backends over the per-conversation workspace. Optional and off by
     * default; resolved system-property-first, then the
     * {@link #FILESYSTEM_ROUTES_ENV} environment variable. Example:
     * {@code memory/=/var/agent-memory,shared/=/var/agent-shared} — reads and
     * writes under {@code memory/} land in a durable store shared across
     * conversations, everything else stays in the bounded per-conversation
     * workspace (deepagents-style composite backend routing).
     */
    public static final String FILESYSTEM_ROUTES_PROPERTY = "org.atmosphere.ai.filesystem.routes";

    /** Environment variable mirroring {@link #FILESYSTEM_ROUTES_PROPERTY}. */
    public static final String FILESYSTEM_ROUTES_ENV = "LLM_FILESYSTEM_ROUTES";

    /** Stable conversation id for the durable routed backends (shared across conversations). */
    private static final String DURABLE_SCOPE = "shared";

    private final Path agentRoot;
    private final AgentFileSystem.Limits limits;
    private final Map<String, Path> routes;
    private final Map<String, AgentFileSystem> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentFileSystem> eldest) {
                    return size() > MAX_CACHED_CONVERSATIONS;
                }
            };

    /**
     * Create a provider for one agent's workspace root.
     *
     * @param agentRoot the agent's workspace root directory
     * @param limits    the bounds applied to every conversation store
     */
    public AgentFileSystemProvider(Path agentRoot, AgentFileSystem.Limits limits) {
        this(agentRoot, limits, resolveRoutes());
    }

    /**
     * Full constructor with explicit composite routes (prefix → durable
     * directory). Package-visible so tests exercise routing without setting
     * process-wide system properties; production callers use the two-arg
     * constructor, which resolves routes from {@link #FILESYSTEM_ROUTES_PROPERTY}.
     */
    AgentFileSystemProvider(Path agentRoot, AgentFileSystem.Limits limits, Map<String, Path> routes) {
        if (agentRoot == null || limits == null) {
            throw new IllegalArgumentException("agentRoot and limits must not be null");
        }
        this.agentRoot = agentRoot;
        this.limits = limits;
        this.routes = routes == null ? Map.of() : Map.copyOf(routes);
    }

    /**
     * The conversation-scoped store rooted at
     * {@code {agentRoot}/files/{conversationId}/}, shared across concurrent
     * callers of the same conversation.
     *
     * @param conversationId the conversation scope (validated as one path segment)
     * @return the scoped filesystem, never {@code null}
     */
    public AgentFileSystem forConversation(String conversationId) {
        synchronized (cache) {
            return cache.computeIfAbsent(conversationId, this::buildForConversation);
        }
    }

    /**
     * Build the view for a conversation: the bounded per-conversation
     * workspace, optionally wrapped in a {@link CompositeAgentFileSystem} that
     * routes configured prefixes to durable shared backends. Each routed
     * backend is itself a bounded {@link WorkspaceAgentFileSystem}, so the
     * per-file / count / total-byte {@link AgentFileSystem.Limits} and
     * traversal guards hold on every route.
     */
    private AgentFileSystem buildForConversation(String conversationId) {
        var workspace = WorkspaceAgentFileSystem.forConversation(agentRoot, conversationId, limits);
        if (routes.isEmpty()) {
            return workspace;
        }
        var routed = new LinkedHashMap<String, AgentFileSystem>();
        for (var route : routes.entrySet()) {
            routed.put(route.getKey(),
                    WorkspaceAgentFileSystem.forConversation(route.getValue(), DURABLE_SCOPE, limits));
        }
        return CompositeAgentFileSystem.of(routed, workspace);
    }

    /**
     * Parse the {@code prefix=dir} route list from
     * {@link #FILESYSTEM_ROUTES_PROPERTY} (system property first, then the
     * {@link #FILESYSTEM_ROUTES_ENV} environment variable). Malformed entries
     * are skipped with a warning rather than failing startup (Correctness
     * Invariant #4). Returns an empty map when unset — the default, in which
     * {@link #forConversation} returns a plain per-conversation workspace with
     * no behavioral change.
     */
    private static Map<String, Path> resolveRoutes() {
        var raw = System.getProperty(FILESYSTEM_ROUTES_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(FILESYSTEM_ROUTES_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        var parsed = new LinkedHashMap<String, Path>();
        for (var entry : raw.split(",")) {
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                logger.warn("Ignoring malformed filesystem route '{}' (expected prefix=dir)", trimmed);
                continue;
            }
            var prefix = trimmed.substring(0, eq).trim();
            var dir = trimmed.substring(eq + 1).trim();
            if (prefix.isEmpty() || dir.isEmpty()) {
                logger.warn("Ignoring malformed filesystem route '{}' (empty prefix or dir)", trimmed);
                continue;
            }
            parsed.put(prefix, Path.of(dir));
        }
        return parsed;
    }

    /** The agent workspace root this provider scopes under (bridge/test use). */
    public Path agentRoot() {
        return agentRoot;
    }

    /** The bounds applied to every conversation store (bridge/test use). */
    public AgentFileSystem.Limits limits() {
        return limits;
    }
}
