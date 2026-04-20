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
        // Long-polling + the default {@code resumeOnBroadcast=true} mean
        // the first {@code resource.write} flushes the response AND
        // resumes the suspended request — every event after the first
        // then lands on an already-closed resource and never reaches the
        // client. Concatenate the buffered events into a single
        // newline-delimited payload so the transport flushes once with
        // every event, preserving per-event boundaries for the client
        // to re-parse. The original per-event order is kept; the single
        // write retains the resume-on-broadcast contract callers expect.
        // Event boundary marker. `\u001e` (INFORMATION SEPARATOR TWO)
        // is the ASCII "record separator" the HTTP streaming community
        // adopts for event-separated text payloads; it is invisible in
        // typical text renderings yet explicit for the client to split
        // on. Chose over `\n` because broadcasters and servlet writers
        // may line-buffer or treat newline as a message terminator on
        // some transports.
        var joined = new StringBuilder();
        for (int i = 0; i < replayed.size(); i++) {
            if (i > 0) {
                joined.append('\u001e');
            }
            joined.append(replayed.get(i).payload());
        }
        // Write directly through the response's servlet output so the
        // payload reaches the reconnecting client without going through
        // the endpoint's broadcaster. The broadcaster would route this
        // back into the @Prompt dispatcher — the same broadcaster that
        // feeds the endpoint's own @Prompt — and trip the handler as
        // if a new user message arrived. Directly writing + flushing
        // keeps the replay scoped to this single reconnecting resource.
        int written = 0;
        try {
            var response = resource.getResponse();
            var writer = response.getWriter();
            writer.write(joined.toString());
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
