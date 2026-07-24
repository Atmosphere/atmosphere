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
import org.atmosphere.a2a.types.TaskStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Mutable, thread-safe in-flight A2A task. The initial state is
 * {@link TaskState#SUBMITTED} (added in v1.0.0); skill execution flips it
 * to {@link TaskState#WORKING} via the first {@link #updateStatus} call.
 */
public final class TaskContext {

    private final String taskId;
    private final String contextId;
    private final long createdAtMillis = System.currentTimeMillis();
    private volatile TaskState state = TaskState.SUBMITTED;
    private volatile String statusMessage;
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final ReentrantLock statusLock = new ReentrantLock();
    private volatile TaskManager taskManager;

    /**
     * Live token sink installed by the streaming dispatch path (SSE). When set,
     * every text token produced during skill execution — pipeline tokens
     * forwarded via {@link A2aStreamCollector#send(String)} and each text part
     * of an {@link #addArtifact} — is forwarded to it as the skill generates
     * output, so the SSE client sees incremental frames instead of a
     * buffer-then-replay after completion. {@code null} (the default) on the
     * unary path makes {@link #emitToken} a no-op.
     */
    private volatile Consumer<String> tokenSink;

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

    /**
     * Merge the initiating message's metadata onto the task (an A2A task inherits
     * its message's metadata). This is how caller intent — e.g. the session
     * tape's {@code atmosphere.tape.parentRunId} carried from a coordinator's
     * fan-out dispatch — reaches downstream consumers. Null/empty is a no-op.
     */
    public void putMetadata(Map<String, Object> extra) {
        if (extra != null && !extra.isEmpty()) {
            metadata.putAll(extra);
        }
    }

    void setTaskManager(TaskManager mgr) {
        this.taskManager = mgr;
    }

    /**
     * Install (or, with {@code null}, detach) the live streaming token sink.
     * Package-private: only the streaming dispatch path in
     * {@link A2aProtocolHandler} installs a sink, and it always detaches it on
     * a terminal path so no token is forwarded after the stream closes
     * (Correctness Invariant #1 — every registration has an explicit removal).
     */
    void setTokenSink(Consumer<String> sink) {
        this.tokenSink = sink;
    }

    /**
     * Forward a token to the installed streaming sink, if any. No-op when no
     * sink is installed (the unary path) or the text is null/empty.
     */
    void emitToken(String text) {
        var sink = tokenSink;
        if (sink != null && text != null && !text.isEmpty()) {
            sink.accept(text);
        }
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
        // Forward each text part to the live streaming sink so an SSE client
        // receives this artifact as an incremental frame the moment the skill
        // produces it, rather than a buffer-then-replay after completion.
        if (artifact != null && artifact.parts() != null) {
            for (var part : artifact.parts()) {
                emitToken(part.text());
            }
        }
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

    /** Snapshot the task as the v1.0.0 wire {@link Task} record. */
    public Task toTask() {
        return new Task(taskId, contextId, TaskStatus.of(state, statusMessage),
                artifacts, messages, metadata);
    }

    /** Snapshot with a clamped history length. {@code historyLength=null} means no limit. */
    public Task toTask(Integer historyLength) {
        if (historyLength == null) {
            return toTask();
        }
        if (historyLength <= 0) {
            return new Task(taskId, contextId, TaskStatus.of(state, statusMessage),
                    artifacts, List.of(), metadata);
        }
        var size = messages.size();
        var fromIndex = Math.max(0, size - historyLength);
        var trimmed = messages.subList(fromIndex, size);
        return new Task(taskId, contextId, TaskStatus.of(state, statusMessage),
                artifacts, List.copyOf(trimmed), metadata);
    }
}
