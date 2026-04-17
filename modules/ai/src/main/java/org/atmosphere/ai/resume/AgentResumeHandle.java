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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Handle to an in-flight agent run that a client may detach from and
 * reattach to. Closes the Correctness Invariant #5 gap identified in v0.5:
 * {@code DurableSessionInterceptor.restoreSession()} reattaches rooms and
 * broadcasters on reconnect, but not in-flight agent runs — {@code runId}
 * threads through {@code StreamingSession} and the
 * {@link RunRegistry} maps it to the live {@link ExecutionHandle} plus an
 * {@link RunEventReplayBuffer} so a reconnecting client gets the events it
 * missed while offline.
 *
 * @param runId         stable identifier for the run (UUID unless caller overrides)
 * @param agentId       the agent that produced this run
 * @param userId        the user that initiated the run
 * @param sessionId     the durable session the run belongs to
 * @param executionHandle the low-level cancel/whenDone surface
 * @param replayBuffer  captured events available for replay on reconnect
 * @param createdAt     when the run started
 */
public record AgentResumeHandle(
        String runId,
        String agentId,
        String userId,
        String sessionId,
        ExecutionHandle executionHandle,
        RunEventReplayBuffer replayBuffer,
        Instant createdAt) {

    public AgentResumeHandle {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(executionHandle, "executionHandle");
        Objects.requireNonNull(replayBuffer, "replayBuffer");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether the run has reached a terminal state. */
    public boolean isDone() {
        return executionHandle.isDone();
    }

    /**
     * Events the replay buffer has captured so far. When a client reconnects
     * with the {@link #runId} it receives these events before the live
     * stream resumes.
     */
    public List<RunEvent> replayableEvents() {
        return replayBuffer.snapshot();
    }
}
