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
 * Wrap-time identity for a taped run, passed to
 * {@link TapeSupport#wrap(org.atmosphere.ai.StreamingSession, TapeRunInfo)}.
 * Use the static factories — one per wrap seam:
 *
 * <ul>
 *   <li>{@link #endpoint} — the {@code @AiEndpoint} handler path. The runId /
 *       userId do not exist at wrap time (they are minted by the RunRegistry
 *       later), so the run is <em>late-bound</em>: the handler calls
 *       {@link TapeRecordingSession#bindRun} right after
 *       {@code session.setRunId(...)}.</li>
 *   <li>{@link #pipeline} — the resource-free {@code AiPipeline.execute} path.
 *       No RunRegistry exists there; a {@code tape-<uuid>} run id is minted at
 *       wrap and the conversation key is the clientId.</li>
 *   <li>{@link #resumed} — the crash-resume re-drive. The runId is known at
 *       wrap time and MUST equal the crashed run's id so the re-drive appends
 *       to the same tape run; a {@code resumed} marker step is recorded first.</li>
 * </ul>
 *
 * @param tapeId       conversation key (endpoint: {@code ai.conversationId}
 *                     attribute else resource uuid; pipeline: clientId)
 * @param resourceUuid originating resource uuid, or {@code null} (pipeline)
 * @param endpoint     endpoint path template or clientId
 * @param model        resolved model name, or {@code null}
 * @param runtimeName  the {@code AgentRuntime} name, or {@code null}
 * @param runId        pre-bound run id, or {@code null} to mint / late-bind
 * @param userId       pre-bound owning principal, or {@code null}
 * @param lateBound    whether run identity arrives via {@code bindRun} after wrap
 * @param resumed      whether this wrap is a crash-resume re-drive
 * @param parentRunId  the dispatching coordinator's tape run id when this run is
 *                     a fan-out child of a multi-agent coordination, else
 *                     {@code null}. Carried onto {@link TapeRun#parentRunId()} so
 *                     the whole team session can be reconstructed as a tree.
 */
public record TapeRunInfo(String tapeId, String resourceUuid, String endpoint, String model,
                          String runtimeName, String runId, String userId,
                          boolean lateBound, boolean resumed, String parentRunId) {

    /** Endpoint-path wrap: run identity is late-bound via {@link TapeRecordingSession#bindRun}. */
    public static TapeRunInfo endpoint(String tapeId, String resourceUuid, String endpoint,
                                       String model, String runtimeName) {
        return new TapeRunInfo(tapeId, resourceUuid, endpoint, model, runtimeName,
                null, null, true, false, null);
    }

    /** Pipeline-path wrap: a {@code tape-<uuid>} run id is minted at wrap time. */
    public static TapeRunInfo pipeline(String clientId, String model, String runtimeName) {
        return pipeline(clientId, model, runtimeName, null);
    }

    /**
     * Pipeline-path wrap for a fan-out child of a multi-agent coordination:
     * links to the dispatching coordinator's tape run via {@code parentRunId}
     * (may be {@code null} for a top-level pipeline run).
     */
    public static TapeRunInfo pipeline(String clientId, String model, String runtimeName,
                                       String parentRunId) {
        return new TapeRunInfo(clientId, null, clientId, model, runtimeName,
                null, null, false, false, parentRunId);
    }

    /** Crash-resume wrap: appends to the SAME run id, recording a {@code resumed} marker first. */
    public static TapeRunInfo resumed(String tapeId, String resourceUuid, String endpoint,
                                      String model, String runtimeName,
                                      String runId, String userId) {
        Objects.requireNonNull(runId, "runId");
        return new TapeRunInfo(tapeId, resourceUuid, endpoint, model, runtimeName,
                runId, userId, false, true, null);
    }
}
