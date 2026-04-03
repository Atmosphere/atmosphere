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
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskState;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutable, thread-safe representation of an in-flight A2A task. Accumulates messages
 * and artifacts, tracks the current {@link TaskState}, and notifies the owning
 * {@link TaskManager} on status and artifact changes.
 */
public final class TaskContext {

    private final String taskId;
    private final String contextId;
    private final long createdAtMillis = System.currentTimeMillis();
    private volatile TaskState state = TaskState.WORKING;
    private volatile String statusMessage;
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final ReentrantLock statusLock = new ReentrantLock();
    private volatile TaskManager taskManager;

    public TaskContext(String taskId, String contextId) {
        this.taskId = taskId;
        this.contextId = contextId;
    }

    public String taskId() {
        return taskId;
    }

    public String contextId() {
        return contextId;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public TaskState state() {
        return state;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    public List<Artifact> artifacts() {
        return Collections.unmodifiableList(artifacts);
    }

    public Map<String, Object> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    void setTaskManager(TaskManager mgr) {
        this.taskManager = mgr;
    }

    public void addMessage(Message msg) {
        messages.add(msg);
    }

    public void updateStatus(TaskState state, String message) {
        statusLock.lock();
        try {
            this.state = state;
            this.statusMessage = message;
        } finally {
            statusLock.unlock();
        }
        if (taskManager != null) {
            taskManager.notifyStatusUpdate(this);
        }
    }

    public void addArtifact(Artifact artifact) {
        artifacts.add(artifact);
        if (taskManager != null) {
            taskManager.notifyArtifactUpdate(this, artifact);
        }
    }

    public void complete(String message) {
        updateStatus(TaskState.COMPLETED, message);
    }

    public void fail(String message) {
        updateStatus(TaskState.FAILED, message);
    }

    public void cancel(String message) {
        updateStatus(TaskState.CANCELED, message);
    }

    public Task toTask() {
        return new Task(taskId, contextId, new Task.TaskStatus(state, statusMessage),
                messages, artifacts, metadata);
    }
}
