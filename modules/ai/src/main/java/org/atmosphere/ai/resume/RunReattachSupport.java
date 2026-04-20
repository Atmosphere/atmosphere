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

    /**
     * Marker value AiEndpointHandler uses when no principal has been
     * resolved for the run. A replay request with a matching anonymous
     * caller gets through (open/demo deployments); but the replay of
     * an authenticated run to an anonymous caller is always refused.
     */
    static final String ANONYMOUS = "anonymous";

    private RunReattachSupport() {
        // Static utility.
    }

    /**
     * Ownership predicate: the reconnecting resource is allowed to
     * replay the run iff its resolved user id matches the user id the
     * registry recorded for the run. An anonymous run (no auth
     * configured) is the one exception — the runId's UUID entropy is
     * the only protection there.
     */
    private static boolean callerOwnsRun(
            org.atmosphere.cpr.AtmosphereRequest req, String runUserId) {
        if (runUserId == null || runUserId.isBlank() || ANONYMOUS.equals(runUserId)) {
            return true;
        }
        var caller = resolveCallerUserId(req);
        return runUserId.equals(caller);
    }

    /**
     * Pull the broadcaster's BroadcastFilter chain off the resource so
     * replay frames can be filtered identically to live frames. When
     * the resource has no broadcaster bound (tests, embedded setups),
     * return an empty list and fall back to unfiltered replay — the
     * ownership check above still protects cross-user exposure.
     */
    private static java.util.List<org.atmosphere.cpr.BroadcastFilter> filtersFor(
            org.atmosphere.cpr.AtmosphereResource resource) {
        try {
            var broadcaster = resource.getBroadcaster();
            if (broadcaster == null) {
                return java.util.List.of();
            }
            return new java.util.ArrayList<>(
                    broadcaster.getBroadcasterConfig().filters());
        } catch (RuntimeException e) {
            return java.util.List.of();
        }
    }

    private static String broadcasterIdFor(org.atmosphere.cpr.AtmosphereResource resource) {
        try {
            var broadcaster = resource.getBroadcaster();
            return broadcaster != null ? broadcaster.getID() : "replay";
        } catch (RuntimeException e) {
            return "replay";
        }
    }

    /**
     * Apply the broadcast-filter chain to a single replay frame.
     * {@code ABORT} drops the frame entirely (content-safety / PII
     * filters use this to strip payloads that shouldn't reach the
     * wire); {@code SKIP} stops iterating but delivers the last
     * transformed message; {@code CONTINUE} threads the transformed
     * message into the next filter.
     *
     * @return the filtered frame as a String ready to write, or
     *         {@code null} when the chain aborted.
     */
    private static String applyFilters(
            java.util.List<org.atmosphere.cpr.BroadcastFilter> filters,
            String broadcasterId, String frame) {
        // The AI-family filters (PiiRedactionFilter, CostMeteringFilter,
        // ContentSafetyFilter) extend AiStreamBroadcastFilter, which
        // pattern-matches on RawMessage + String JSON. Wrap so the
        // filter's guard-clause accepts the input and reaches the
        // concrete filterAiMessage logic. Filters that don't recognize
        // RawMessage pass through unchanged — same as the live path.
        Object current = new org.atmosphere.cpr.RawMessage(frame);
        Object original = current;
        for (var filter : filters) {
            try {
                var action = filter.filter(broadcasterId, original, current);
                if (action == null) {
                    continue;
                }
                if (action.action() == org.atmosphere.cpr.BroadcastFilter.BroadcastAction.ACTION.ABORT) {
                    return null;
                }
                current = action.message();
                if (action.action() == org.atmosphere.cpr.BroadcastFilter.BroadcastAction.ACTION.SKIP) {
                    break;
                }
            } catch (RuntimeException e) {
                logger.warn("Replay filter {} threw: {}",
                        filter.getClass().getName(), e.getMessage());
            }
        }
        // Unwrap the final payload: the filter chain may pass through a
        // RawMessage or the bare JSON string depending on which filter
        // fired last.
        if (current == null) {
            return null;
        }
        if (current instanceof org.atmosphere.cpr.RawMessage raw) {
            var inner = raw.message();
            return inner == null ? null : inner.toString();
        }
        return current.toString();
    }

    /**
     * Resolve the reconnecting caller's user id from the same attribute
     * chain {@link org.atmosphere.ai.processor.AiEndpointHandler}
     * populates on the dispatch path ({@code ai.userId}, then
     * {@code org.atmosphere.auth.principal}, then the servlet
     * {@code getUserPrincipal()}). Returns {@code null} when no
     * principal is resolvable.
     */
    private static String resolveCallerUserId(org.atmosphere.cpr.AtmosphereRequest req) {
        var attr = req.getAttribute("ai.userId");
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        var auth = req.getAttribute("org.atmosphere.auth.principal");
        if (auth instanceof java.security.Principal p
                && p.getName() != null && !p.getName().isBlank()) {
            return p.getName();
        }
        try {
            var userPrincipal = req.getUserPrincipal();
            if (userPrincipal != null && userPrincipal.getName() != null
                    && !userPrincipal.getName().isBlank()) {
                return userPrincipal.getName();
            }
        } catch (RuntimeException ignored) {
            // Some servlet containers throw ISE if the principal is not
            // yet resolved on the thread — treat as anonymous.
        }
        return null;
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
        var handle = handleOpt.get();
        // Ownership check: the run id is a bearer token — if the
        // registry trusts it without principal binding, any caller who
        // obtains or guesses a run id can replay another user's
        // stream. Enforce that the requesting resource's resolved user
        // matches the user who originated the run. Anonymous-runner
        // runs (userId equals "anonymous") are the only exception —
        // those deployments run without auth and the protection
        // reduces to the runId's own UUID entropy. Any authenticated
        // run MUST match.
        if (!callerOwnsRun(req, handle.userId())) {
            logger.warn("Reattach refused — resource {} does not own run {} "
                    + "(run userId={}, caller userId={})",
                    resource.uuid(), runId, handle.userId(), resolveCallerUserId(req));
            return 0;
        }
        var replayed = handle.replayableEvents();
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
            // Route each frame through the broadcaster's filter chain
            // before writing so response-path protections (PII
            // redaction, content safety, etc.) apply to replayed
            // frames identically to live frames. Skipping the chain
            // would let replay expose content that the live path would
            // have redacted — a P0 cross-session data leak risk. Aborts
            // drop the frame but preserve ordering for the rest.
            var filters = filtersFor(resource);
            var broadcasterId = broadcasterIdFor(resource);
            long seq = 0;
            var batch = new StringBuilder();
            for (var ev : replayed) {
                seq++;
                var msg = new org.atmosphere.ai.filter.AiStreamMessage(
                        ev.type(), ev.payload(), runId, seq, null, null);
                var json = applyFilters(filters, broadcasterId, msg.toJson());
                if (json != null) {
                    batch.append(json).append('\n');
                    written++;
                }
            }
            // Single write so long-polling's resume-on-broadcast fires
            // exactly once after every event is flushed — per-event
            // writes would flush+close on the first one and drop the
            // rest.
            if (batch.length() > 0) {
                writer.write(batch.toString());
                writer.flush();
            }
        } catch (java.io.IOException | RuntimeException e) {
            logger.warn("Replay to resource {} failed: {}",
                    resource.uuid(), e.getMessage());
            written = 0;
        }
        logger.info("Reattach run {} for resource {}: replayed {}/{} event(s)",
                runId, resource.uuid(), written, replayed.size());
        return written;
    }
}
