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
 * frame or when the reconnected resource disconnects. A run that already
 * finished stays replayable for the registry TTL
 * ({@link RunRegistry#lookupReplayable}), so a late reconnect gets the whole
 * tail. A long-polling poll is answered once: it gets the events of the run
 * it was not handed yet, in one write, and is resumed so the response reaches
 * the client now rather than at the poll's suspend timeout. When there are
 * none, the poll waits like any other one: for the run's next event, written
 * to it alone and resumed at once, or, for a run that already ended, for a
 * reply or its suspend timeout. Waiting polls take none of the places of the
 * streaming reattaches ({@link RunEventReplayBuffer#awaitNext}); past
 * {@link RunEventReplayBuffer#MAX_WAITING_POLLS} tracking ids waiting on one
 * run, a poll waits for a reply or its suspend timeout too, and its client's
 * next poll gets what it missed. Each event goes to a tracking id once
 * ({@link RunEventReplayBuffer#claimNew}), so a client that re-sends the run id
 * on every poll, as atmosphere.js does until it reads a terminal frame, is not
 * answered with the same replay over and over.</p>
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
 * cancels it.</p>
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
     * if the run is still going.
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
            logger.warn("Reattach refused — resource {} does not own run {} "
                    + "(run userId={}, caller userId={})",
                    resource.uuid(), runId, handle.userId(), resolveCallerUserId(req));
            return 0;
        }
        if (!handle.isDone() && forwardsLiveEvents(resource)) {
            return replayAndForward(resource, runId, handle, reattachGrace(registry));
        }
        if (!handle.isDone()) {
            // A long-polling client listens to the run at most while one poll
            // waits: nothing would detach the run again if it never polled
            // again, so its grace restarts from this poll instead of ending.
            RunDisconnectGrace.restart(runId, reattachGrace(registry));
        }
        var transport = resource.transport();
        if (transport != null && Utils.resumableTransport(transport)) {
            return new PollReplay(resource, handle, filtersFor(resource), broadcasterIdFor(resource),
                    reattachGrace(registry)).start();
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
     * wait; past {@link RunEventReplayBuffer#MAX_WAITING_POLLS} tracking ids
     * waiting, it is left to a reply or its suspend timeout. Frames carry the event's own sequence, plus one,
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
        private boolean ended;
        private Runnable unsubscribe = () -> { };

        PollReplay(AtmosphereResource resource, AgentResumeHandle run,
                   java.util.List<org.atmosphere.cpr.BroadcastFilter> filters, String broadcasterId,
                   java.time.Duration grace) {
            this.resource = resource;
            this.run = run;
            this.runId = run.runId();
            this.consumer = resource.uuid();
            this.buffer = run.replayBuffer();
            this.filters = filters;
            this.broadcasterId = broadcasterId;
            this.grace = grace;
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
            var subscription = consumer != null ? buffer.awaitNext(consumer, this)
                    : new RunEventReplayBuffer.Subscription(buffer.snapshot(), false, () -> { });
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
            if (!run.isDone() && buffer.subscriberCount() == 0) {
                RunDisconnectGrace.redetach(runId, grace);
            }
        }
    }

    /** The reattach grace, bounded by the registry TTL (Correctness Invariant #3). */
    static java.time.Duration reattachGrace(RunRegistry registry) {
        var grace = org.atmosphere.ai.AiConfig.resolveReattachGrace();
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
     * The forwarder's lock is held from before the subscription until the
     * backlog is written, so an event captured meanwhile waits and lands after
     * the backlog, in order.
     */
    private static int replayAndForward(AtmosphereResource resource, String runId,
                                        AgentResumeHandle handle, java.time.Duration grace) {
        var forwarder = new LiveForwarder(resource, handle, filtersFor(resource),
                broadcasterIdFor(resource), grace);
        synchronized (forwarder) {
            var subscription = handle.replayBuffer().subscribe(forwarder::forward);
            forwarder.cancelWith(subscription.cancel());
            // The client is back: the run its disconnect left going is not
            // cancelled when the grace expires. After the subscription, so a
            // disconnect noticed from now on finds a listener and runs no grace.
            RunDisconnectGrace.reattached(runId);
            var written = 0;
            for (var event : subscription.backlog()) {
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
                    + "forwarding the rest of the run: {}", runId, resource.uuid(), written,
                    subscription.backlog().size(), !forwarder.isCancelled());
            return written;
        }
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
        private final org.atmosphere.cpr.AtmosphereResourceEventListener disconnectListener;
        private long seq;
        private boolean cancelled;
        private Runnable unsubscribe = () -> { };

        LiveForwarder(AtmosphereResource resource, AgentResumeHandle run,
                      java.util.List<org.atmosphere.cpr.BroadcastFilter> filters,
                      String broadcasterId, java.time.Duration grace) {
            this.resource = resource;
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
