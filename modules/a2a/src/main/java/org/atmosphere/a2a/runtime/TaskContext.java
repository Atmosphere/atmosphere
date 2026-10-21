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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>A terminal state ({@link TaskState#isTerminal()}) is final: a later
 * {@link #complete}, {@link #fail} or {@link #updateStatus} is ignored. A
 * {@link #cancel} also stops the running skill: it runs the
 * {@linkplain #onCancel cancel listeners} (the dispatcher interrupts the
 * skill's thread, which cancels an AI pipeline's in-flight model call), and
 * {@link #isCancelled()} answers {@code true} so a skill and the model
 * client it drives can stop at their next step.</p>
 */
public final class TaskContext {

    private static final Logger logger = LoggerFactory.getLogger(TaskContext.class);

    private final String taskId;
    private final String contextId;
    private final long createdAtMillis = System.currentTimeMillis();
    private volatile TaskState state = TaskState.SUBMITTED;
    private volatile String statusMessage;
    /**
     * The status as of the last transition: its timestamp is when the task
     * entered its state, not when it was read.
     */
    private volatile TaskStatus status = TaskStatus.of(TaskState.SUBMITTED);
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final ReentrantLock statusLock = new ReentrantLock();
    private volatile TaskManager taskManager;
    private volatile boolean cancelled;
    private final List<Runnable> cancelListeners = new CopyOnWriteArrayList<>();
    /** Whether a token was streamed live through {@link #emitToken}. */
    private volatile boolean tokensStreamed;

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

    /** The status as of the last transition, stamped when the task entered its state. */
    public TaskStatus status() {
        return status;
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
     * Stream a piece of the skill's answer to a {@code SendStreamingMessage}
     * client as it is produced. No-op when no stream is attached (the unary
     * path), the text is null/empty, or the task was cancelled. Once a skill
     * streamed its answer this way, an {@link #addArtifact} carrying the whole
     * answer is recorded on the task without being streamed a second time.
     *
     * @param text the next piece of the answer
     */
    public void emitToken(String text) {
        if (forward(text)) {
            tokensStreamed = true;
        }
    }

    private boolean forward(String text) {
        var sink = tokenSink;
        if (sink != null && text != null && !text.isEmpty() && !cancelled) {
            sink.accept(text);
            return true;
        }
        return false;
    }

    /**
     * Whether this task was cancelled ({@code CancelTask}). A skill that runs
     * a long step (a model call, a loop) checks it to stop early; nothing it
     * produces after the cancel reaches the task.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Run {@code listener} when this task is cancelled, or at once when it
     * already is. Remove it with {@link #removeCancelListener} when the work
     * it would stop is over.
     */
    public void onCancel(Runnable listener) {
        cancelListeners.add(listener);
        if (cancelled && cancelListeners.remove(listener)) {
            listener.run();
        }
    }

    /** Remove a listener added with {@link #onCancel}. */
    public void removeCancelListener(Runnable listener) {
        cancelListeners.remove(listener);
    }

    public void addMessage(Message msg) {
        messages.add(msg);
    }

    public void updateStatus(TaskState state, String message) {
        if (!transition(state, message)) {
            return;
        }
        if (taskManager != null) {
            taskManager.notifyStatusUpdate(this);
        }
    }

    /** Apply a status unless the task already reached a terminal state. */
    private boolean transition(TaskState next, String message) {
        statusLock.lock();
        try {
            if (this.state.isTerminal()) {
                logger.debug("Task {} is {}: ignoring the later {} ({})",
                        taskId, this.state, next, message);
                return false;
            }
            this.state = next;
            this.statusMessage = message;
            this.status = TaskStatus.of(next, message);
            return true;
        } finally {
            statusLock.unlock();
        }
    }

    public void addArtifact(Artifact artifact) {
        if (cancelled) {
            logger.debug("Task {} was cancelled: dropping a later artifact", taskId);
            return;
        }
        artifacts.add(artifact);
        // Forward each text part to the live streaming sink so an SSE client
        // receives this artifact as an incremental frame the moment the skill
        // produces it, rather than a buffer-then-replay after completion. A
        // skill that already streamed its answer token by token is not sent
        // the whole answer again.
        if (artifact != null && artifact.parts() != null && !tokensStreamed) {
            for (var part : artifact.parts()) {
                forward(part.text());
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

    /**
     * Cancel this task: it ends {@link TaskState#CANCELED} (unless it already
     * ended) and its {@linkplain #onCancel cancel listeners} stop the running
     * skill.
     */
    public void cancel(String message) {
        if (!transition(TaskState.CANCELED, message)) {
            return;
        }
        cancelled = true;
        for (var listener : cancelListeners) {
            if (cancelListeners.remove(listener)) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    logger.warn("Cancel listener of task {} failed", taskId, e);
                }
            }
        }
        if (taskManager != null) {
            taskManager.notifyStatusUpdate(this);
        }
    }

    /** Snapshot the task as the v1.0.0 wire {@link Task} record. */
    public Task toTask() {
        return new Task(taskId, contextId, status,
                artifacts, messages, metadata);
    }

    /** Snapshot with a clamped history length. {@code historyLength=null} means no limit. */
    public Task toTask(Integer historyLength) {
        if (historyLength == null) {
            return toTask();
        }
        if (historyLength <= 0) {
            return new Task(taskId, contextId, status,
                    artifacts, List.of(), metadata);
        }
        var size = messages.size();
        var fromIndex = Math.max(0, size - historyLength);
        var trimmed = messages.subList(fromIndex, size);
        return new Task(taskId, contextId, status,
                artifacts, List.copyOf(trimmed), metadata);
    }
}
