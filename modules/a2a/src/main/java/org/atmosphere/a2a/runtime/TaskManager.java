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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskArtifactUpdateEvent;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.a2a.types.TaskStatus;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Lifecycle manager for A2A tasks. Status / artifact events are emitted as
 * v1.0.0 spec-shaped {@link TaskStatusUpdateEvent} /
 * {@link TaskArtifactUpdateEvent} carrying both {@code taskId} and
 * {@code contextId}.
 */
public final class TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);
    private static final int DEFAULT_MAX_TASKS = 10_000;
    private static final long COMPLETED_TASK_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();
    private final List<Consumer<TaskStatusUpdateEvent>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TaskArtifactUpdateEvent>> artifactListeners = new CopyOnWriteArrayList<>();
    private final int maxTasks;
    private final ScheduledExecutorService cleaner;

    public TaskManager() {
        this(DEFAULT_MAX_TASKS);
    }

    public TaskManager(int maxTasks) {
        this.maxTasks = maxTasks;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "a2a-task-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::evictStaleTasks, 1, 1, TimeUnit.MINUTES);
    }

    public TaskContext createTask(String contextId) {
        if (tasks.size() >= maxTasks) {
            throw new IllegalStateException("Task limit reached (" + maxTasks + "); cannot create new task");
        }
        var taskId = UUID.randomUUID().toString();
        var ctx = new TaskContext(taskId, contextId);
        ctx.setTaskManager(this);
        tasks.put(taskId, ctx);
        logger.debug("Task created: {} in context {}", taskId, contextId);
        return ctx;
    }

    public Optional<TaskContext> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<TaskContext> listTasks(String contextId) {
        if (contextId == null) {
            return List.copyOf(tasks.values());
        }
        return tasks.values().stream()
                .filter(t -> contextId.equals(t.contextId()))
                .toList();
    }

    /** Filter tasks by context + state; null filters are unconstrained. */
    public List<TaskContext> listTasks(String contextId, TaskState status) {
        return tasks.values().stream()
                .filter(t -> contextId == null || contextId.equals(t.contextId()))
                .filter(t -> status == null || status == t.state())
                .toList();
    }

    public boolean cancelTask(String taskId) {
        var task = tasks.get(taskId);
        if (task != null && (task.state() == TaskState.WORKING
                || task.state() == TaskState.SUBMITTED)) {
            task.cancel("Canceled by request");
            return true;
        }
        return false;
    }

    public void onStatusUpdate(Consumer<TaskStatusUpdateEvent> listener) {
        statusListeners.add(listener);
    }

    public void onArtifactUpdate(Consumer<TaskArtifactUpdateEvent> listener) {
        artifactListeners.add(listener);
    }

    void notifyStatusUpdate(TaskContext task) {
        var event = new TaskStatusUpdateEvent(task.taskId(), task.contextId(),
                TaskStatus.of(task.state(), task.statusMessage()));
        for (var listener : statusListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Status listener failed for task {}", task.taskId(), e);
            }
        }
    }

    void notifyArtifactUpdate(TaskContext task, Artifact artifact) {
        var event = new TaskArtifactUpdateEvent(task.taskId(), task.contextId(), artifact);
        for (var listener : artifactListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Artifact listener failed for task {}", task.taskId(), e);
            }
        }
    }

    private void evictStaleTasks() {
        var now = System.currentTimeMillis();
        var iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var ctx = entry.getValue();
            if (ctx.state().isTerminal()
                    && (now - ctx.createdAtMillis()) > COMPLETED_TASK_TTL_MS) {
                iterator.remove();
                logger.debug("Evicted stale task {} (state={})", entry.getKey(), ctx.state());
            }
        }
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}
