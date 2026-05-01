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
package org.atmosphere.mcp.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store + lifecycle manager for {@link McpTask} instances (MCP
 * spec 2025-11-25, experimental). Holds the active task population, evicts
 * expired tasks via a background sweep, and exposes the methods the
 * {@code tasks/*} JSON-RPC handlers need.
 *
 * <p>This is deliberately a single-process registry. Multi-node deployments
 * that need cross-replica task continuity should plug a distributed
 * implementation in front of this class — the wire methods only depend on
 * the small surface exposed here.</p>
 *
 * @since 4.0.43
 */
public final class McpTaskManager {

    private static final Logger logger = LoggerFactory.getLogger(McpTaskManager.class);

    /** Default task lifetime if the requestor doesn't specify {@code ttl}. */
    public static final long DEFAULT_TTL_MS = 60_000L;
    /** Hard ceiling on requested TTL — receivers MAY override per spec. */
    public static final long MAX_TTL_MS = 60 * 60 * 1000L; // 1 hour
    /** Suggested poll interval baked into responses. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 1_000L;

    private final java.util.Map<String, McpTask> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final long maxTtlMs;
    private final Long pollIntervalMs;

    public McpTaskManager() {
        this(MAX_TTL_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    public McpTaskManager(long maxTtlMs, Long pollIntervalMs) {
        this.maxTtlMs = maxTtlMs;
        this.pollIntervalMs = pollIntervalMs;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofPlatform().daemon().name("mcp-task-cleaner").unstarted(r);
            return t;
        });
        // Every 30s, sweep expired tasks. Cheap on small N; for larger
        // populations swap in a min-heap by createdAt+ttl.
        this.cleaner.scheduleAtFixedRate(this::evictExpired,
                30, 30, TimeUnit.SECONDS);
    }

    /**
     * Create a new task with the requestor's desired TTL clamped to our
     * maximum. Always starts in {@link McpTask.Status#WORKING}.
     */
    public McpTask create(long requestedTtlMs) {
        long effective = requestedTtlMs <= 0
                ? DEFAULT_TTL_MS
                : Math.min(requestedTtlMs, maxTtlMs);
        var task = new McpTask(effective, pollIntervalMs);
        tasks.put(task.taskId(), task);
        logger.debug("Created MCP task {} ttl={}ms", task.taskId(), effective);
        return task;
    }

    public Optional<McpTask> get(String taskId) {
        var task = tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }
        if (task.isExpired() && !task.status().isTerminal()) {
            // Defensive: an expired non-terminal task is treated as gone.
            // Spec lets us delete at any time after TTL.
            tasks.remove(taskId);
            return Optional.empty();
        }
        return Optional.of(task);
    }

    /**
     * Cancel a task by id. Returns {@code Optional.empty()} when the id is
     * unknown or already terminal — caller should map that to the spec's
     * {@code -32602 Invalid params} error.
     */
    public Optional<McpTask> cancel(String taskId, String reason) {
        var task = tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }
        if (task.status().isTerminal()) {
            return Optional.empty();
        }
        task.cancel(reason != null ? reason : "Cancelled by request");
        return Optional.of(task);
    }

    /**
     * Listing for {@code tasks/list}. Returns up to {@code limit} entries
     * starting after {@code afterId} (cursor-based). Spec-mandated:
     * cursors are opaque tokens to the requestor; we just use task ids.
     *
     * @param afterId  the cursor from the previous response, or {@code null} for the first page
     * @param limit    max items to return
     * @return list of (task, nextCursor) — nextCursor is {@code null} when no more
     */
    public Page list(String afterId, int limit) {
        var sorted = new ArrayList<>(tasks.values());
        sorted.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        int startIdx = 0;
        if (afterId != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (afterId.equals(sorted.get(i).taskId())) {
                    startIdx = i + 1;
                    break;
                }
            }
        }
        var page = new ArrayList<McpTask>();
        for (int i = startIdx; i < sorted.size() && page.size() < limit; i++) {
            page.add(sorted.get(i));
        }
        var endIdx = startIdx + page.size();
        String nextCursor = endIdx < sorted.size() ? sorted.get(endIdx - 1).taskId() : null;
        return new Page(List.copyOf(page), nextCursor);
    }

    /** Visible-for-testing snapshot. */
    public int size() {
        return tasks.size();
    }

    public void shutdown() {
        cleaner.shutdownNow();
        tasks.clear();
    }

    private void evictExpired() {
        var it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                logger.debug("Evicted expired MCP task {}", entry.getKey());
            }
        }
    }

    /**
     * Single page of {@code tasks/list} results.
     *
     * @param tasks      this page's tasks (already wire-shaped via {@link McpTask#toWire()})
     * @param nextCursor opaque cursor for the next page, or {@code null}
     */
    public record Page(List<McpTask> tasks, String nextCursor) {
        public Map<String, Object> toWire() {
            var taskList = new ArrayList<Map<String, Object>>(tasks.size());
            for (var t : tasks) {
                taskList.add(t.toWire());
            }
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("tasks", taskList);
            if (nextCursor != null) {
                m.put("nextCursor", nextCursor);
            }
            return m;
        }
    }
}
