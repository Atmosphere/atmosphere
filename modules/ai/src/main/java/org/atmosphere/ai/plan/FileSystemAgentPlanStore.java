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
package org.atmosphere.ai.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Default {@link AgentPlanStore}: persists each plan as one JSON document at
 * {@code {root}/plans/{agentId}/{conversationId}.json} under the agent
 * workspace subtree, serialized through the same Jackson mapper family the
 * wire uses ({@code tools.jackson}).
 *
 * <h2>Boundary safety</h2>
 * Both keys are validated at the boundary (reject blank, {@code ..} and path
 * separators) and the resolved path is normalized and containment-checked
 * against the store root — the same guards as
 * {@code FileSystemAgentState.resolveSafe} (Correctness Invariant #4).
 *
 * <h2>Bounds</h2>
 * Hard bounds reject over-limit writes with a clear message (Correctness
 * Invariant #3): at most {@value #MAX_STEPS} steps per plan, at most
 * {@value #MAX_PLAN_BYTES} serialized bytes per plan, and at most
 * {@value #MAX_PLAN_FILES} plan files per store.
 *
 * <h2>Thread safety</h2>
 * Per-file locks serialize writes to the same plan document; reads do not
 * lock. A corrupt or unreadable document reads as empty (fail-safe read).
 */
public final class FileSystemAgentPlanStore implements AgentPlanStore {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemAgentPlanStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum steps a stored plan may carry. */
    public static final int MAX_STEPS = 256;

    /** Maximum serialized size of one plan document, in bytes. */
    public static final int MAX_PLAN_BYTES = 128 * 1024;

    /** Maximum number of plan documents one store keeps before evicting the oldest. */
    public static final int MAX_PLAN_FILES = 1024;

    /** Upper bound on cached per-file locks (keys arrive from external input). */
    static final int MAX_CACHED_LOCKS = 256;

    private final Path root;
    private final Path plansDir;
    /**
     * Per-file write locks, LRU-bounded — conversation ids arrive from
     * external input, and an unbounded map fed by external input is a DoS
     * vector (Invariant #3). An evicted-while-held lock can at worst let two
     * concurrent writers race one file: full-document writes are atomic
     * (temp + move), so the loser's document simply wins last — never a torn
     * read.
     */
    private final Map<Path, ReentrantLock> locks =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, ReentrantLock> eldest) {
                    return size() > MAX_CACHED_LOCKS;
                }
            });

    /**
     * Create a store rooted at the given workspace directory. The
     * {@code plans/} subtree is created lazily on first write.
     *
     * @param root absolute path of the agent workspace root
     */
    public FileSystemAgentPlanStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.plansDir = this.root.resolve("plans");
    }

    @Override
    public Optional<AgentPlan> get(String agentId, String conversationId) {
        var path = planPath(agentId, conversationId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            var json = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(MAPPER.readValue(json, AgentPlan.class));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            logger.warn("Failed to read plan {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String agentId, String conversationId, AgentPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        if (plan.steps().size() > MAX_STEPS) {
            throw new IllegalArgumentException("plan rejected: " + plan.steps().size()
                    + " steps exceeds the " + MAX_STEPS + "-step limit");
        }
        var json = MAPPER.writeValueAsString(plan);
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PLAN_BYTES) {
            throw new IllegalArgumentException("plan rejected: " + bytes.length
                    + " bytes exceeds the " + MAX_PLAN_BYTES + "-byte limit");
        }
        var path = planPath(agentId, conversationId);
        var lock = locks.computeIfAbsent(path, k -> new ReentrantLock());
        lock.lock();
        try {
            if (!Files.exists(path)) {
                // Bounded store, never bricked: evict the least-recently
                // modified plan documents to make room instead of rejecting
                // every new conversation forever once the cap fills
                // (Invariants #3 and #2 — external input must not grow the
                // store unboundedly, and hitting the cap must not be a
                // permanent terminal state).
                evictOldestPlans();
            }
            Files.createDirectories(path.getParent());
            // Same-directory temp + atomic move: lock-free readers (the admin
            // plan endpoint, console polling) must never observe a torn
            // document, and a crash mid-write must not lose the previous plan.
            var temp = Files.createTempFile(path.getParent(), ".plan-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist plan " + path, e);
        } finally {
            lock.unlock();
        }
    }

    /** Delete least-recently-modified plan documents until under the cap. */
    private void evictOldestPlans() throws IOException {
        if (!Files.isDirectory(plansDir)) {
            return;
        }
        java.util.List<Path> plans;
        try (Stream<Path> walk = Files.walk(plansDir)) {
            plans = walk.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparingLong(FileSystemAgentPlanStore::lastModified))
                    .toList();
        }
        var excess = plans.size() - (MAX_PLAN_FILES - 1);
        for (var plan : plans) {
            if (excess <= 0) {
                return;
            }
            try {
                Files.deleteIfExists(plan);
                logger.info("Evicted plan document {} (over the {}-plan cap)",
                        plan, MAX_PLAN_FILES);
                excess--;
            } catch (IOException e) {
                logger.warn("Could not evict plan document {}: {}", plan, e.getMessage());
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

    private Path planPath(String agentId, String conversationId) {
        var safeAgent = validateSegment("agentId", agentId);
        var safeConversation = validateSegment("conversationId", conversationId);
        return resolveSafe(plansDir.resolve(safeAgent), safeConversation + ".json");
    }

    private static String validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " contains illegal path characters: " + value);
        }
        return value;
    }

    private Path resolveSafe(Path parent, String segment) {
        var resolved = parent.resolve(segment).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace: " + resolved);
        }
        return resolved;
    }
}
