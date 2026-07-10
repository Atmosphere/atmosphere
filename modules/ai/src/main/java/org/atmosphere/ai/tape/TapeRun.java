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
package org.atmosphere.ai.tape;

import java.util.Objects;

/**
 * Persisted metadata row for one taped run — one {@code @Prompt} turn on the
 * endpoint path, one {@code AiPipeline.execute} call on the pipeline path, or
 * one crash-resume re-drive appending to an existing run.
 *
 * <p>Timestamps are INTEGER epoch millis everywhere (the {@code run_meta.created_at}
 * precedent — avoids the ISO-8601 TEXT sort trap).</p>
 *
 * @param runId        stable run identifier; on the endpoint path this equals the
 *                     {@code RunRegistry} / EffectJournal run id (provenance join)
 * @param tapeId       conversation key the run belongs to — endpoint: the
 *                     {@code ai.conversationId} attribute else the resource uuid;
 *                     pipeline: the clientId
 * @param sessionId    the leaf session's per-prompt UUID; may be {@code null}
 * @param resourceUuid originating {@code AtmosphereResource} uuid; {@code null}
 *                     on the resource-free pipeline path
 * @param userId       principal that owns the run; {@code null} when unknown
 * @param endpoint     endpoint path template (endpoint path) or clientId
 *                     (pipeline path); may be {@code null}
 * @param model        resolved model name; may be {@code null}
 * @param runtimeName  the {@code AgentRuntime} name; may be {@code null}
 * @param startedAt    epoch millis when the run began
 * @param status       lifecycle status; terminal statuses are write-once
 * @param endedAt      epoch millis when the run reached its terminal status;
 *                     {@code null} while {@link TapeStatus#OPEN}
 * @param stepCount    number of steps persisted for the run
 * @param droppedSteps steps produced but not persisted (queue overflow,
 *                     append-after-terminal, store failure, step cap)
 * @param truncated    whether the per-run step cap stopped recording
 * @param parentRunId  the run this one was forked from, or {@code null}
 */
public record TapeRun(String runId, String tapeId, String sessionId, String resourceUuid,
                      String userId, String endpoint, String model, String runtimeName,
                      long startedAt, TapeStatus status, Long endedAt,
                      long stepCount, long droppedSteps, boolean truncated,
                      String parentRunId) {

    public TapeRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
    }
}
