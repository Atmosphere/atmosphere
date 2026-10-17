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
import org.atmosphere.util.Utils;
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
 *
 * <p>The run itself keeps writing into the session bound to the connection
 * that went away. On a streaming transport (WebSocket, SSE, streaming) a
 * reattach to a run still in flight therefore also subscribes the
 * reconnected resource to the run's later events through its terminal
 * {@code complete} / {@code error} frame; the subscription ends with that
 * frame or when the reconnected resource disconnects. A resource suspended on
 * the broadcaster of the run's {@code broadcastReply} room is written the
 * backlog only: the room broadcasts it the rest of the run, whose room session
 * belongs to the run and outlives the connection of its prompter
 * ({@code DefaultStreamingSession.cleanupResource} leaves it open). A run that already
 * finished stays replayable for the registry TTL
 * ({@link RunRegistry#lookupReplayable}), so a late reconnect gets the whole
 * tail. A long-polling poll is answered once: it gets the events of the run
 * it was not handed yet, in one write, and is resumed so the response reaches
 * the client now rather than at the poll's suspend timeout. When there are
 * none, the poll waits like any other one: for the run's next event, written
 * to it alone and resumed at once, or, for a run that already ended, for a
 * reply or its suspend timeout. Waiting polls take none of the places of the
 * streaming reattaches ({@link RunEventReplayBuffer#awaitNext}); past
 * {@link RunEventReplayBuffer#maxWaitingPolls()} tracking ids waiting on one
 * run, a poll waits for a reply or its suspend timeout too, and its client's
 * next poll gets what it missed. Each event goes to a tracking id once
 * ({@link RunEventReplayBuffer#claimNew}), so a client that re-sends the run id
 * on every poll, as atmosphere.js does until it reads a terminal frame, is not
 * answered with the same replay over and over; the frames of the run a poll of
 * it received as a long-polling reply count as handed to it too, when they
 * follow on from what it was handed before without a gap
 * ({@link RunEventReplayBuffer#handed}), for at most
 * {@link RunEventReplayBuffer#maxReplayPositions()} tracking ids, apart from
 * the ones a poll carrying the run id was handed events to.</p>
 *
 * <p>The events are what {@link RunEventCapturingSession} captures: the text
 * and the terminal frame the client received, whether the {@code @Prompt}
 * method sent them or the runtime streamed them for {@code session.stream(...)}.
 * A client whose connection was lost, rather than closed on purpose, leaves
 * its run going for the reattach grace ({@link RunDisconnectGrace}): a reattach
 * within it rescinds the pending cancel, and the reattached client receives the
 * whole answer through {@code complete}. When the grace expires first, the
 * model call is cancelled and a reattached client receives the text streamed
 * until then, then the {@code cancelled} frame. A reattached client that loses
 * its own connection detaches the run again; one that leaves on purpose
 * cancels it. Only the run's own client ({@code ownClient}) rescinds,
 * restarts or holds off the grace: in a {@code broadcastReply} room every
 * member is handed the run id, and a member other than the prompter that
 * reattaches with it, on any transport, is replayed what it missed and nothing
 * more. The prompter is its tracking id, or, in a room whose run has an
 * authenticated owner, any connection of that user, so a reloaded page, which
 * atmosphere.js gives a new tracking id, still keeps its run going; in an
 * anonymous room a reloaded page is replayed what it missed, but its run is
 * cancelled when the grace ends.</p>
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

    /**
     * Request attribute {@link #markRoomJoin} sets: the sequence of the last
     * event of the run a reconnecting connection carries the id of, read once
     * the connection was suspended, and so had joined the room of a
     * {@code broadcastReply} run. The room broadcasts the events after it to the
     * connection, so its replay writes the events up to it only.
     */
    public static final String ROOM_JOIN_MARK_ATTRIBUTE = "org.atmosphere.ai.runRoomJoinMark";

    private static final Logger logger = LoggerFactory.getLogger(RunReattachSupport.class);

    /**
     * The run id a reconnecting client carries (request attribute set by
     * {@code DurableSessionInterceptor}, or the {@code X-Atmosphere-Run-Id}
     * header), or {@code null} when none is present. Shared by the live-replay
     * path and the crash-resume path so both read the id identically.
     */
    public static String pendingRunId(org.atmosphere.cpr.AtmosphereResource resource) {
        if (resource == null || resource.getRequest() == null) {
            return null;
        }
        var req = resource.getRequest();
        Object attr = req.getAttribute(RUN_ID_ATTRIBUTE);
        if (attr == null) {
            attr = req.getHeader(RUN_ID_HEADER);
        }
        return attr instanceof String runId && !runId.isBlank() ? runId : null;
    }

    /**
     * Marker value AiEndpointHandler uses when the container reported
     * no principal for the run — no auth is configured. A replay
     * request with a matching anonymous caller gets through (open/demo
     * deployments); but the replay of an authenticated run to an
     * anonymous caller is always refused.
     */
    static final String ANONYMOUS = "anonymous";

    /**
     * Marker value AiEndpointHandler uses when the container REFUSED to
     * resolve the principal (some servlet containers throw ISE when the
     * principal is not yet bound to the dispatching thread — routine
     * under Atmosphere's async/virtual-thread dispatch). Unlike
     * {@link #ANONYMOUS}, this does not mean "no auth configured" — it
     * means "unknown", so such runs fail closed: no caller can ever
     * match this owner and reattach is always refused. Deployments that
     * hit this set {@code ai.userId} via an AtmosphereInterceptor.
     */
    public static final String UNRESOLVED = "unresolved-principal";

    private RunReattachSupport() {
        // Static utility.
    }

    /**
     * Record, on the request of {@code resource}, just suspended (so a member of
     * its broadcaster), how far the run it carries the id of had got
     * ({@link #ROOM_JOIN_MARK_ATTRIBUTE}). Every event of the run is captured
     * before it is broadcast, so the events after the mark reach a member of the
     * run's room through the room; a reattach that also replayed them wrote them
     * twice, and out of order. A no-op without a run id or a replayable run.
     *
     * @param resource the connection, just suspended
     * @param registry the registry to look the run id up in
     */
    public static void markRoomJoin(AtmosphereResource resource, RunRegistry registry) {
        var runId = pendingRunId(resource);
        if (runId == null || registry == null) {
            return;
        }
        var run = registry.lookupReplayable(runId);
        if (run.isEmpty()) {
            return;
        }
        try {
            resource.getRequest().setAttribute(ROOM_JOIN_MARK_ATTRIBUTE, run.get().replayBuffer().lastSequence());
        } catch (RuntimeException e) {
            logger.trace("Unable to mark when {} joined the room of run {}", resource.uuid(), runId, e);
        }
    }

    /**
     * The events of {@code backlog} a member of the run's room that joined it at
     * {@link #ROOM_JOIN_MARK_ATTRIBUTE} did not receive through the room: those up
     * to the mark; all of them when no mark was recorded.
     */
    private static java.util.List<RunEvent> beforeRoomJoin(AtmosphereResource resource,
                                                           java.util.List<RunEvent> backlog) {
        Object mark;
        try {
            mark = resource.getRequest() != null
                    ? resource.getRequest().getAttribute(ROOM_JOIN_MARK_ATTRIBUTE) : null;
        } catch (RuntimeException e) {
            logger.trace("Unable to read when {} joined its room", resource.uuid(), e);
            mark = null;
        }
        if (!(mark instanceof Long joined)) {
            return backlog;
        }
        return backlog.stream().filter(event -> event.sequence() <= joined).toList();
    }

    /**
     * Ownership predicate: the reconnecting resource is allowed to
     * replay the run iff its resolved user id matches the user id the
     * registry recorded for the run. An anonymous run (no auth
     * configured) is the one exception — the runId's UUID entropy is
     * the only protection there. A run whose owner could not be
     * resolved at registration ({@link #UNRESOLVED}) fails closed:
     * ownership is indeterminate, so no caller is ever a match.
     */
    private static boolean callerOwnsRun(
            org.atmosphere.cpr.AtmosphereRequest req, String runUserId) {
        if (UNRESOLVED.equals(runUserId)) {
            return false;
        }
        if (runUserId == null || runUserId.isBlank() || ANONYMOUS.equals(runUserId)) {
            return true;
        }
        var caller = resolveCallerUserId(req);
        return runUserId.equals(caller);
    }

    /**
     * Whether {@code resource} is the run's own client, the one whose reattach
     * holds the run's reattach grace off ({@link RunDisconnectGrace}): any caller
     * that owns the run ({@code callerOwnsRun}) for a run whose reply reaches its
     * own client only, since nobody else is handed its id; for a run whose reply
     * is broadcast to a room ({@link RunEventReplayBuffer#forRoomRun}), whose
     * every member is handed its id, the tracking id that sent the prompt
     * ({@link AgentResumeHandle#sessionId()}) and, when the run has an
     * authenticated owner, any caller authenticated as that user
     * ({@code callerOwnsRun}, which refuses every other user before this is
     * asked): atmosphere.js keeps its tracking id in memory only, so a reloaded
     * page comes back under a new one. Another member reattaching with the run
     * id is a bystander: it is replayed what it missed, but neither restarts the
     * grace nor listens to the run, so the run of a prompter that left for good
     * is cancelled when the grace ends, whatever transport the room's other
     * members use. In an anonymous room (no identity) only the tracking id tells
     * the prompter apart, so a prompter whose reloaded page comes back under
     * another tracking id is a bystander of its own room run.
     */
    static boolean ownClient(AtmosphereResource resource, AgentResumeHandle handle) {
        if (handle.replayBuffer().roomId() == null || handle.sessionId().equals(resource.uuid())) {
            return true;
        }
        var owner = handle.userId();
        return owner != null && !owner.isBlank() && !ANONYMOUS.equals(owner) && !UNRESOLVED.equals(owner)
                && resource.getRequest() != null && callerOwnsRun(resource.getRequest(), owner);
    }

    /**
     * Pull the broadcaster's BroadcastFilter chain off the resource so
     * replay frames can be filtered identically to live frames. When
     * the resource has no broadcaster bound (tests, embedded setups),
     * return an empty list and fall back to unfiltered replay — the
     * ownership check above still protects cross-user exposure.
     * {@link org.atmosphere.cpr.ClusterBroadcastFilter}s are left out:
     * the replay belongs to this one resource, and a cluster filter would
     * have every other node deliver the run's private frames to all its
     * subscribers.
     */
    private static java.util.List<org.atmosphere.cpr.BroadcastFilter> filtersFor(
            org.atmosphere.cpr.AtmosphereResource resource) {
        try {
            var broadcaster = resource.getBroadcaster();
            if (broadcaster == null) {
                return java.util.List.of();
            }
            var filters = new java.util.ArrayList<org.atmosphere.cpr.BroadcastFilter>();
            for (var filter : broadcaster.getBroadcasterConfig().filters()) {
                if (!(filter instanceof org.atmosphere.cpr.ClusterBroadcastFilter)) {
                    filters.add(filter);
                }
            }
            return filters;
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
     * Apply the broadcast-filter chain to a single replay frame (also each
     * frame a long-polling reply buffers, {@link org.atmosphere.ai.LongPollingReplies}).
     * {@code ABORT} drops the frame entirely (content-safety / PII
     * filters use this to strip payloads that shouldn't reach the
     * wire); {@code SKIP} stops iterating but delivers the last
     * transformed message; {@code CONTINUE} threads the transformed
     * message into the next filter.
     *
     * @return the filtered frame as a String ready to write, or
     *         {@code null} when the chain aborted.
     */
    public static String applyFilters(
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
     * empty. A long-polling poll is written only the events not handed to its
     * tracking id yet, and resumed when any were written; with none it stays
     * suspended, returns {@code 0}, and is answered with the run's next event,
     * if the run is still going; the poll of the run's own client that is a
     * long-polling member of a {@code broadcastReply} room waits on the run too,
     * but is written none of its events: the room's frames reach the member's
     * polls already. The reattach of a room member other than the run's own
     * client ({@code ownClient}) is written what it missed, and neither
     * restarts, rescinds nor holds off the run's reattach grace.
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
        var handleOpt = registry.lookupReplayable(runId);
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
            // Once per (run, tracking id): a member of an authenticated room
            // polls with the run id of another user's run on every poll.
            if (handle.replayBuffer().firstRefusal(resource.uuid())) {
                logger.warn("Reattach refused — resource {} does not own run {} "
                        + "(run userId={}, caller userId={}); its further refusals for this run log at DEBUG",
                        resource.uuid(), runId, handle.userId(), resolveCallerUserId(req));
            } else {
                logger.debug("Reattach refused — resource {} does not own run {} (run userId={}, caller userId={})",
                        resource.uuid(), runId, handle.userId(), resolveCallerUserId(req));
            }
            return 0;
        }
        var own = ownClient(resource, handle);
        if (!handle.isDone() && forwardsLiveEvents(resource)) {
            return replayAndForward(resource, runId, handle, reattachGrace(resource, registry), own);
        }
        if (!handle.isDone() && own) {
            // A long-polling client listens to the run at most while one poll
            // waits: nothing would detach the run again if it never polled
            // again, so its grace restarts from this poll instead of ending.
            RunDisconnectGrace.restart(runId, reattachGrace(resource, registry));
        }
        var transport = resource.transport();
        if (transport != null && Utils.resumableTransport(transport)) {
            return new PollReplay(resource, handle, filtersFor(resource), broadcasterIdFor(resource),
                    reattachGrace(resource, registry), own).start();
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
        // type name ("streaming-text" / "complete" / "error" / "cancelled") so the
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
            // One write of the whole backlog.
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

    /**
     * Answers one long-polling poll that carries a run id with the events of
     * the run its tracking id was not handed yet. With none, it waits for the
     * run's next event, until the poll ends (a reply, its suspend timeout, a
     * close), the run does, or a newer poll of its tracking id takes over the
     * wait; past {@link RunEventReplayBuffer#maxWaitingPolls()} tracking ids
     * waiting, it is left to a reply or its suspend timeout. The poll of the
     * run's own client that is a long-polling member of a {@code broadcastReply}
     * room ({@code org.atmosphere.ai.LongPollingRoomReplies}) waits on the run as
     * well, so it listens to it like any waiting poll, but is written none of
     * the run's events: the room's frames reach that poll, or are kept for the
     * member's next one, already, and the room resumes it. The poll of another
     * client of a room run ({@code ownClient}) never waits on the run: it is
     * written what it missed, if anything, and left to the room, so it neither
     * listens to the run nor detaches it again. Frames carry the event's own sequence, plus one,
     * so the pieces a client gets over several polls number consistently.
     * While it waits it listens to the run, which keeps a pending grace from
     * firing; when it ends with the run still going and nobody else listening,
     * the run is detached again for the grace, so a client that stops polling
     * still has its run cancelled.
     */
    private static final class PollReplay implements java.util.function.Consumer<RunEvent> {

        private final AtmosphereResource resource;
        private final AgentResumeHandle run;
        private final String runId;
        private final String consumer;
        private final RunEventReplayBuffer buffer;
        private final java.util.List<org.atmosphere.cpr.BroadcastFilter> filters;
        private final String broadcasterId;
        private final java.time.Duration grace;
        private final org.atmosphere.cpr.AtmosphereResourceEventListener endListener;
        /**
         * The listener of a room member's poll: it holds the run watched while
         * the poll waits, and neither claims nor writes the run's events, which
         * the room delivers to the member's polls. One per poll, so an older
         * poll of the same tracking id ending never removes a newer one's wait.
         */
        private final java.util.function.Consumer<RunEvent> watchOnly;
        /** The poll is the run's own client's ({@code ownClient}): only such a poll waits on the run. */
        private final boolean own;
        private boolean ended;
        private Runnable unsubscribe = () -> { };

        PollReplay(AtmosphereResource resource, AgentResumeHandle run,
                   java.util.List<org.atmosphere.cpr.BroadcastFilter> filters, String broadcasterId,
                   java.time.Duration grace, boolean own) {
            this.resource = resource;
            this.run = run;
            this.own = own;
            this.runId = run.runId();
            this.consumer = resource.uuid();
            this.buffer = run.replayBuffer();
            this.filters = filters;
            this.broadcasterId = broadcasterId;
            this.grace = grace;
            this.watchOnly = event -> logger.trace("Run {} event {} reaches the poll of room member {} through"
                    + " the room", runId, event.sequence(), consumer);
            this.endListener = new org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onResume(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                    end();
                }

                @Override
                public void onDisconnect(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                    end();
                }

                @Override
                public void onClose(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                    end();
                }
            };
        }

        /** Answer the poll with what is new, or wait for the run's next event; the events written now. */
        synchronized int start() {
            // Subscribed before claiming: an event captured in between is either
            // claimed here or delivered to accept, and claimed once. A poll with
            // no tracking id does not wait: it takes what the run holds.
            // The poll of a member of a room (a broadcastReply room) waits too,
            // so the run counts as watched and a pending grace stands down, but
            // is written nothing: the room's frames reach its polls already, and
            // the run's next frame written here as well would reach the client
            // twice. The room resumes the poll, which ends the wait.
            // A bystander's poll (a room member other than the run's own client)
            // does not wait on the run at all: it would hold off the grace of a
            // prompter gone for good, and the room's frames reach it already.
            RunEventReplayBuffer.Subscription subscription;
            if (consumer == null) {
                subscription = new RunEventReplayBuffer.Subscription(buffer.snapshot(), false, () -> { });
            } else if (own) {
                subscription = buffer.awaitNext(consumer, roomMember() ? watchOnly : this);
            } else {
                subscription = new RunEventReplayBuffer.Subscription(java.util.List.of(), false, () -> { });
            }
            unsubscribe = subscription.cancel();
            var claimed = consumer != null ? buffer.claimNew(consumer) : subscription.backlog();
            var written = claimed.isEmpty() ? 0 : writeAndResume(claimed);
            if (written != 0 || consumer == null || !subscription.live()) {
                end();
                if (written > 0) {
                    logger.info("Reattach run {} for resource {}: replayed {}/{} new event(s)",
                            runId, resource.uuid(), written, claimed.size());
                } else {
                    logger.debug("Reattach run {} for resource {}: no new event to replay",
                            runId, resource.uuid());
                }
                return Math.max(written, 0);
            }
            resource.addEventListener(endListener);
            if (!resource.isSuspended()) {
                end();
            }
            logger.debug("Reattach run {} for resource {}: nothing new; the poll waits for the run's next event",
                    runId, resource.uuid());
            return 0;
        }

        /** Whether the poll's client is a long-polling member of the room the poll is suspended on. */
        private boolean roomMember() {
            try {
                return org.atmosphere.ai.LongPollingRoomReplies.isMember(resource.getBroadcaster(), consumer);
            } catch (RuntimeException e) {
                logger.trace("Unable to read the broadcaster of {}", resource.uuid(), e);
                return false;
            }
        }

        /** The run's next event: answer the poll with it. */
        @Override
        public synchronized void accept(RunEvent event) {
            if (ended || !resource.isSuspended() || !buffer.claim(consumer, event)) {
                return;
            }
            if (writeAndResume(java.util.List.of(event)) != 0 || RunEventReplayBuffer.isTerminal(event.type())) {
                end();
            }
        }

        /**
         * One write of {@code events} through the filter chain, then resume the
         * poll: the events written, or {@code -1} when the write failed (the
         * events are then given back to the consumer).
         */
        private int writeAndResume(java.util.List<RunEvent> events) {
            var batch = new StringBuilder();
            var written = 0;
            for (var event : events) {
                var msg = new org.atmosphere.ai.filter.AiStreamMessage(
                        event.type(), event.payload(), runId, event.sequence() + 1, null, null);
                var json = applyFilters(filters, broadcasterId, msg.toJson());
                if (json != null) {
                    batch.append(json).append('\n');
                    written++;
                }
            }
            if (written == 0) {
                return 0;
            }
            try {
                var writer = resource.getResponse().getWriter();
                writer.write(batch.toString());
                writer.flush();
                if (writer.checkError()) {
                    throw new java.io.IOException("writer reported an error");
                }
            } catch (java.io.IOException | RuntimeException e) {
                logger.warn("Replay of run {} to resource {} failed: {}", runId, resource.uuid(), e.getMessage());
                if (consumer != null) {
                    buffer.unclaim(consumer, events.get(0).sequence());
                }
                return -1;
            }
            // Written straight to the response, not broadcast, so no
            // resume-on-broadcast ends the poll: resume it here, or the client
            // sees the replay only at the poll's suspend timeout.
            if (resource.isSuspended()) {
                resource.resume();
            }
            return written;
        }

        synchronized void end() {
            if (ended) {
                return;
            }
            ended = true;
            unsubscribe.run();
            try {
                resource.removeEventListener(endListener);
            } catch (RuntimeException e) {
                logger.trace("Removing the reattach listener from {} failed", resource.uuid(), e);
            }
            // Listening, this poll may have stood a grace down; with nobody
            // listening now, the run waits out the grace again (a no-op for a
            // run that was never detached), and the client's next poll restarts it.
            if (own && !run.isDone() && buffer.subscriberCount() == 0) {
                RunDisconnectGrace.redetach(runId, grace);
            }
        }
    }

    /**
     * The reattach grace of the application {@code resource} belongs to,
     * bounded by the registry TTL (Correctness Invariant #3). The disconnect
     * reads the same application's value.
     */
    static java.time.Duration reattachGrace(AtmosphereResource resource, RunRegistry registry) {
        var environment = org.atmosphere.ai.AiEnvironment.find(resource.getAtmosphereConfig())
                .orElseGet(org.atmosphere.ai.AiEnvironment::current);
        var grace = environment.resolveReattachGrace();
        return grace.compareTo(registry.ttl()) > 0 ? registry.ttl() : grace;
    }

    /** Transports whose response stays open after a write, so later frames can follow. */
    private static boolean forwardsLiveEvents(AtmosphereResource resource) {
        var transport = resource.transport();
        return transport == AtmosphereResource.TRANSPORT.WEBSOCKET
                || transport == AtmosphereResource.TRANSPORT.SSE
                || transport == AtmosphereResource.TRANSPORT.STREAMING;
    }

    /**
     * Write the backlog of a run still in flight, then keep forwarding its
     * events to {@code resource} until the run ends or the resource goes away.
     * A member of the run's {@code broadcastReply} room (the resource is
     * suspended on the room's broadcaster) is written the backlog only: the
     * room broadcasts it the rest of the run, metadata, tool events and approval
     * requests included, so forwarding the run's frames as well would write each
     * of them twice. The run's own client still listens to the run until its
     * terminal frame or its disconnect, as a forwarded reattach does, so it
     * holds off the grace, and its disconnect detaches the run again.
     * The forwarder's lock is held from before the subscription until the
     * backlog is written, so an event captured meanwhile waits and lands after
     * the backlog, in order.
     */
    private static int replayAndForward(AtmosphereResource resource, String runId,
                                        AgentResumeHandle handle, java.time.Duration grace, boolean own) {
        var broadcasterId = broadcasterIdFor(resource);
        // A member of the run's room is delivered the rest of the run by the
        // room's broadcaster: forwarding it too would write each frame twice.
        var roomMember = broadcasterId.equals(handle.replayBuffer().roomId());
        var forwarder = new LiveForwarder(resource, handle, filtersFor(resource),
                broadcasterId, grace, !roomMember);
        if (!own) {
            return replayToBystander(resource, runId, handle, forwarder, roomMember);
        }
        synchronized (forwarder) {
            var subscription = handle.replayBuffer().subscribe(forwarder::forward);
            forwarder.cancelWith(subscription.cancel());
            // The client is back: the run its disconnect left going is not
            // cancelled when the grace expires. After the subscription, so a
            // disconnect noticed from now on finds a listener and runs no grace.
            RunDisconnectGrace.reattached(runId);
            // The run's session is registered under this connection too, so an
            // operator's cancel addressed to it reaches the run. Its departure
            // only drops that registration: this forwarder's own listener ends
            // the run once nobody listens and the connection it started on is gone.
            org.atmosphere.ai.AiStreamingSession.reattached(runId, resource.uuid());
            var written = 0;
            // A member of the room received the events after it joined through the room.
            var backlog = roomMember ? beforeRoomJoin(resource, subscription.backlog()) : subscription.backlog();
            for (var event : backlog) {
                var outcome = forwarder.write(event);
                if (outcome == Write.FAILED) {
                    break;
                }
                if (outcome == Write.WRITTEN) {
                    written++;
                }
            }
            if (subscription.live() && !forwarder.isCancelled()) {
                forwarder.attach();
            } else {
                forwarder.cancel();
            }
            logger.info("Reattach run {} for resource {}: replayed {}/{} event(s), "
                    + "following the rest of the run: {}", runId, resource.uuid(), written,
                    backlog.size(), !forwarder.isCancelled());
            return written;
        }
    }

    /**
     * Write the backlog of a room run to a member other than its own client
     * ({@code ownClient}), and nothing more: the room broadcasts the rest of the
     * run to it, and the member neither rescinds nor holds off the run's grace.
     */
    private static int replayToBystander(AtmosphereResource resource, String runId,
                                         AgentResumeHandle handle, LiveForwarder forwarder, boolean roomMember) {
        var snapshot = handle.replayBuffer().snapshot();
        // A member of the room received the events after it joined through the room.
        var backlog = roomMember ? beforeRoomJoin(resource, snapshot) : snapshot;
        var written = 0;
        synchronized (forwarder) {
            for (var event : backlog) {
                var outcome = forwarder.write(event);
                if (outcome == Write.FAILED) {
                    break;
                }
                if (outcome == Write.WRITTEN) {
                    written++;
                }
            }
            forwarder.cancel();
        }
        logger.info("Reattach run {} for room member {}: replayed {}/{} event(s); the room delivers the rest",
                runId, resource.uuid(), written, backlog.size());
        return written;
    }

    /** Outcome of writing one frame to a reattached resource. */
    private enum Write { WRITTEN, FILTERED, FAILED }

    /**
     * Writes one run's frames to one reattached resource and owns that
     * subscription: it removes the buffer listener and its own resource
     * listener on the terminal frame, on a failed write and on disconnect.
     * When the resource disconnects while the run is still going and no other
     * reattached client is listening, the run is detached again for the grace
     * (a lost connection) or cancelled (a client that left on purpose), as the
     * disconnect of the client that started it would.
     */
    private static final class LiveForwarder {

        private final AtmosphereResource resource;
        private final String runId;
        private final AgentResumeHandle run;
        private final java.util.List<org.atmosphere.cpr.BroadcastFilter> filters;
        private final String broadcasterId;
        private final java.time.Duration grace;
        /** {@code false} for a member of the run's room: it listens to the run, the room writes it the frames. */
        private final boolean writesLive;
        private final org.atmosphere.cpr.AtmosphereResourceEventListener disconnectListener;
        private long seq;
        private boolean cancelled;
        private Runnable unsubscribe = () -> { };

        LiveForwarder(AtmosphereResource resource, AgentResumeHandle run,
                      java.util.List<org.atmosphere.cpr.BroadcastFilter> filters,
                      String broadcasterId, java.time.Duration grace, boolean writesLive) {
            this.resource = resource;
            this.writesLive = writesLive;
            this.runId = run.runId();
            this.run = run;
            this.filters = filters;
            this.broadcasterId = broadcasterId;
            this.grace = grace;
            this.disconnectListener = new org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onDisconnect(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                    cancel();
                    clientGone(event);
                }

                @Override
                public void onClose(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                    cancel();
                }
            };
        }

        /** The reattached client went away: the run is detached or cancelled once nobody listens. */
        private void clientGone(org.atmosphere.cpr.AtmosphereResourceEvent event) {
            if (run.isDone() || run.replayBuffer().subscriberCount() > 0) {
                return;
            }
            if (RunDisconnectGrace.isTransportLoss(event)) {
                if (!RunDisconnectGrace.redetach(runId, grace)) {
                    // Grace disabled: the run's own client is gone for good.
                    RunDisconnectGrace.expireNow(runId);
                }
            } else {
                RunDisconnectGrace.expireNow(runId);
            }
        }

        synchronized void cancelWith(Runnable unsubscribe) {
            this.unsubscribe = unsubscribe;
        }

        synchronized void attach() {
            resource.addEventListener(disconnectListener);
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        /** Buffer listener: forward one live event, ending the subscription on a terminal one. */
        synchronized void forward(RunEvent event) {
            if (cancelled) {
                return;
            }
            if (!writesLive) {
                logger.trace("Run {} event {} reaches room member {} through the room", runId, event.sequence(),
                        resource.uuid());
                if (RunEventReplayBuffer.isTerminal(event.type())) {
                    cancel();
                }
                return;
            }
            if (write(event) == Write.FAILED || RunEventReplayBuffer.isTerminal(event.type())) {
                cancel();
            }
        }

        /** Write one frame through the filter chain; a failed write cancels. */
        synchronized Write write(RunEvent event) {
            seq++;
            var msg = new org.atmosphere.ai.filter.AiStreamMessage(
                    event.type(), event.payload(), runId, seq, null, null);
            var json = applyFilters(filters, broadcasterId, msg.toJson());
            if (json == null) {
                return Write.FILTERED;
            }
            try {
                var writer = resource.getResponse().getWriter();
                writer.write(json + "\n");
                writer.flush();
                if (writer.checkError()) {
                    throw new java.io.IOException("writer reported an error");
                }
                return Write.WRITTEN;
            } catch (java.io.IOException | RuntimeException e) {
                logger.warn("Forwarding run {} to resource {} failed; stopping: {}",
                        runId, resource.uuid(), e.getMessage());
                cancel();
                return Write.FAILED;
            }
        }

        synchronized void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            unsubscribe.run();
            try {
                resource.removeEventListener(disconnectListener);
            } catch (RuntimeException e) {
                logger.trace("Removing the reattach listener from {} failed", resource.uuid(), e);
            }
        }
    }
}
