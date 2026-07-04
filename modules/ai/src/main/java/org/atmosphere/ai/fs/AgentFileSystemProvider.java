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

    /** Upper bound on cached conversation views. */
    public static final int MAX_CACHED_CONVERSATIONS = 256;

    private final Path agentRoot;
    private final AgentFileSystem.Limits limits;
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
        if (agentRoot == null || limits == null) {
            throw new IllegalArgumentException("agentRoot and limits must not be null");
        }
        this.agentRoot = agentRoot;
        this.limits = limits;
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
            return cache.computeIfAbsent(conversationId,
                    id -> WorkspaceAgentFileSystem.forConversation(agentRoot, id, limits));
        }
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
