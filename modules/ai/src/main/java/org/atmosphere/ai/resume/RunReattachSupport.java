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

import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless helper for the run-reattach consumer path. Extracted from
 * {@code AiEndpointHandler.reattachPendingRun} so the replay-on-reconnect
 * semantics are directly unit-testable without standing up a full
 * {@code AiEndpointHandler} (runtime + session + pipeline + lifecycle).
 *
 * <p>On reconnection, a client carries {@code X-Atmosphere-Run-Id} —
 * either as an HTTP header or the request attribute set by
 * {@code DurableSessionInterceptor}. This helper looks the run id up in
 * the {@link RunRegistry}, drains the run's replay buffer, and writes
 * each buffered event's payload directly to the reconnecting resource —
 * bypassing the broadcaster because peer subscribers already saw the
 * events live. Returns the number of events replayed (0 when no run id,
 * unknown run id, or empty buffer).</p>
 */
public final class RunReattachSupport {

    /**
     * Request-attribute key {@code DurableSessionInterceptor} sets after
     * extracting {@code X-Atmosphere-Run-Id} from the reconnection
     * request. Inlined here so the {@code ai} module does not pull a
     * compile-time dependency on the {@code durable-sessions} module.
     */
    public static final String RUN_ID_ATTRIBUTE = "org.atmosphere.session.runId";
    /** HTTP header fallback for clients that bypass the interceptor. */
    public static final String RUN_ID_HEADER = "X-Atmosphere-Run-Id";

    private static final Logger logger = LoggerFactory.getLogger(RunReattachSupport.class);

    private RunReattachSupport() {
        // Static utility.
    }

    /**
     * Replay buffered events from the run named by the incoming
     * request attribute / header onto {@code resource}. Returns the
     * number of events successfully written; {@code 0} when no run id
     * is present, the run is unknown or completed, or the buffer is
     * empty.
     *
     * @param resource the reconnecting resource
     * @param registry the registry to look the run id up in
     * @return number of events replayed
     */
    public static int replayPendingRun(AtmosphereResource resource, RunRegistry registry) {
        if (resource == null || registry == null || resource.getRequest() == null) {
            return 0;
        }
        var req = resource.getRequest();
        Object attr = req.getAttribute(RUN_ID_ATTRIBUTE);
        if (attr == null) {
            attr = req.getHeader(RUN_ID_HEADER);
        }
        if (!(attr instanceof String runId) || runId.isBlank()) {
            return 0;
        }
        var handleOpt = registry.lookup(runId);
        if (handleOpt.isEmpty()) {
            logger.debug("Reconnect with run id {} but no live run in the registry "
                    + "(expired or never existed); treating as fresh session", runId);
            return 0;
        }
        var replayed = handleOpt.get().replayableEvents();
        if (replayed.isEmpty()) {
            logger.info("Reattach run {} for resource {}: no buffered events to replay",
                    runId, resource.uuid());
            return 0;
        }
        // Emit each buffered event as a proper AiStreamMessage JSON
        // frame — the same wire shape DefaultStreamingSession.send and
        // .complete produce on the live path. The frontend parser
        // treats this endpoint as a stream of newline-delimited JSON
        // events; an opaque concatenated-text payload would fail to
        // parse at all. RunEvent.type is captured as the wire-protocol
        // type name ("streaming-text" / "complete" / "error") so the
        // mapping is direct. Sequence numbers restart at 1 for the
        // replay run — the client correlates by sessionId, and the
        // replayed sessionId is the run id so a reconnecting client
        // can distinguish replay frames from live frames.
        int written = 0;
        try {
            var response = resource.getResponse();
            var writer = response.getWriter();
            long seq = 0;
            var batch = new StringBuilder();
            for (var ev : replayed) {
                seq++;
                var msg = new org.atmosphere.ai.filter.AiStreamMessage(
                        ev.type(), ev.payload(), runId, seq, null, null);
                batch.append(msg.toJson()).append('\n');
            }
            // Single write so long-polling's resume-on-broadcast fires
            // exactly once after every event is flushed — per-event
            // writes would flush+close on the first one and drop the
            // rest.
            writer.write(batch.toString());
            writer.flush();
            written = replayed.size();
        } catch (java.io.IOException | RuntimeException e) {
            logger.warn("Replay to resource {} failed: {}",
                    resource.uuid(), e.getMessage());
        }
        logger.info("Reattach run {} for resource {}: replayed {}/{} event(s)",
                runId, resource.uuid(), written, replayed.size());
        return written;
    }
}
