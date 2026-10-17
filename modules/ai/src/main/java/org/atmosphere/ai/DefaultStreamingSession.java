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
package org.atmosphere.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link StreamingSession} that writes
 * JSON-encoded streaming messages directly to an {@link AtmosphereResource}.
 *
 * <p>By default, messages are delivered only to the originating resource via
 * {@code broadcaster.broadcast(msg, Set.of(resource))}. This ensures each
 * client receives only its own AI response while still passing through
 * the broadcaster's filter/cache chain.</p>
 *
 * <p>When constructed in room-broadcast mode (used by
 * {@code @AiEndpoint(broadcastReply = true)} for shared rooms), the reply is
 * instead delivered to every subscriber on the resource's (per-path)
 * broadcaster via {@code broadcaster.broadcast(msg)} — one reply fans out to
 * the whole room. The prompt is still dispatched to a single {@code @Prompt}
 * handler upstream, so the model runs exactly once regardless of room size.
 * For a broadcaster-only session with no originating resource, use
 * {@link BroadcasterStreamingSession}.</p>
 *
 * <p>A session whose originating resource is a long-polling poll does not
 * stream: a poll is answered by the first frame written to it. Its frames run
 * through the broadcaster's filters as they are produced, are kept in a bounded
 * buffer, and are handed over complete, at the terminal frame, at an
 * {@code approval-required} frame and at the {@code X-Atmosphere-Run-Id}
 * metadata frame (so a client that loses its connection mid-reply knows the run
 * to reattach to), to the client's next poll; see {@link LongPollingReplies}. A room session broadcasts its frames to the room
 * whatever its originating resource's transport, and the room keeps them for
 * each of its long-polling members, the originating client included; see
 * {@link LongPollingRoomReplies}.</p>
 *
 * <p>Wire protocol:</p>
 * <pre>
 * {"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}
 * {"type":"progress","data":"Thinking...","sessionId":"abc-123","seq":2}
 * {"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}
 * {"type":"complete","sessionId":"abc-123","seq":4}
 * {"type":"complete","data":"Full response","sessionId":"abc-123","seq":5}
 * {"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}
 * {"type":"cancelled","data":"disconnect","sessionId":"abc-123","seq":7}
 * {"type":"prompt","text":"What is 2+2?","from":""from":"&lt;prompter uuid&gt;"lt;prompter room member id"from":"&lt;prompter uuid&gt;"gt;","sessionId":"abc-123","seq":1}
 * </pre>
 *
 * <p>The {@code prompt} frame is sent by a room session only ({@link #sendPrompt}).</p>
 */
public final class DefaultStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, AtmosphereResource> SESSION_RESOURCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DefaultStreamingSession> SESSION_INSTANCES = new ConcurrentHashMap<>();
    /**
     * Where the frames of a session no live instance answers for go while one of
     * them runs through the broadcaster's filters: a topic session's terminal
     * frame, or a frame a filter deferred; see {@link #broadcastPlaced}.
     */
    private static final ConcurrentHashMap<String, Placement> PLACED = new ConcurrentHashMap<>();

    private final String sessionId;
    private final AtmosphereResource resource;
    private final boolean broadcastToRoom;
    /**
     * The room a room session broadcasts to, read when the session starts and
     * replaced by the room live under the same id once it is destroyed;
     * {@code null} for any other session, or when it could not be read. Held
     * rather than read from the resource on each frame: the resource recreates
     * a destroyed broadcaster under the same id when asked for it, and a
     * session that outlives its room must not bring the room back.
     */
    private volatile Broadcaster room;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean errored = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);
    /** The reply kept for a long-polling client, or {@code null} when the session streams. */
    private final LongPollingReplies.Buffer longPolling;
    /** The identity the long-polling connection was suspended under; its reply goes only to it. */
    private final Object longPollingOwner;
    /** Guards {@link #longPolling} while a frame runs through the filters and is buffered. */
    private final Object longPollingLock = new Object();
    /** Frames a filter deferred while the frame being buffered ran through it. */
    private final ArrayList<String> deferredFrames = new ArrayList<>();
    /** Bounds the frames filters may defer past one frame. */
    private static final int MAX_DEFERRAL_ROUNDS = 8;
    /**
     * Last outbound-activity timestamp (epoch millis), refreshed on every
     * broadcast so {@link #sweepExpired} never reaps a session that is still
     * streaming. Package-private volatile so tests can age a session without
     * a clock indirection on this hot path.
     */
    volatile long lastActivityMillis = System.currentTimeMillis();

    DefaultStreamingSession(String sessionId, AtmosphereResource resource) {
        this(sessionId, resource, false);
    }

    /**
     * @param broadcastToRoom when {@code true}, the reply fans out to every
     *                        subscriber on the resource's broadcaster (the
     *                        room) instead of only the originating resource
     */
    DefaultStreamingSession(String sessionId, AtmosphereResource resource, boolean broadcastToRoom) {
        this.sessionId = sessionId;
        this.resource = resource;
        this.broadcastToRoom = broadcastToRoom;
        if (!broadcastToRoom && isLongPolling(resource)) {
            this.longPolling = new LongPollingReplies.Buffer(resource.uuid(),
                    LongPollingReplies.Limits.of(resource.getAtmosphereConfig()));
            this.longPollingOwner = connectionOwner(resource);
        } else {
            this.longPolling = null;
            this.longPollingOwner = null;
        }
        if (broadcastToRoom) {
            this.room = roomOf(resource);
            // The room's long-polling members receive the reply whole; see LongPollingRoomReplies.
            if (room != null) {
                installRoom(room, resource);
            }
        } else {
            this.room = null;
        }
        SESSION_RESOURCES.put(sessionId, resource);
        SESSION_INSTANCES.put(sessionId, this);
        StreamingSessionSweeper.ensureStarted();
    }

    /**
     * Look up the {@link AtmosphereResource} for a given session ID.
     * Used by broadcast filters to deliver deferred messages via unicast.
     *
     * @param sessionId the streaming session identifier
     * @return the resource, or empty if no active session with that ID
     */
    public static Optional<AtmosphereResource> resourceForSession(String sessionId) {
        return Optional.ofNullable(SESSION_RESOURCES.get(sessionId));
    }

    /**
     * Where a session's frames go: its originating resource only, or every
     * subscriber of the broadcaster (a room session, or a topic session started
     * with {@code StreamingSessions.start(Broadcaster)}).
     *
     * @param resource the originating resource, or {@code null} for a topic
     *                 session, which has none
     * @param toRoom   whether the session fans its frames out to every subscriber
     */
    public record Delivery(AtmosphereResource resource, boolean toRoom) {
    }

    /** One placement of a session's frames; compared by identity, so each one removes only itself. */
    private static final class Placement {
        private final Delivery delivery;

        private Placement(Delivery delivery) {
            this.delivery = delivery;
        }
    }

    /**
     * How a live session delivers its frames. The session stays registered while
     * its terminal frame runs through the broadcaster's filters, so a filter
     * that defers a frame past the terminal one reads it there; it is empty once
     * the session completed, failed or was cleaned up. A topic session is
     * answered only while its terminal frame runs through the filters, and a
     * session whose frame a filter deferred only while that frame runs through
     * them ({@link #broadcastPlaced}), so the next filter may defer it again.
     *
     * @param sessionId the streaming session identifier
     * @return the delivery, or empty if no active session with that ID
     */
    public static Optional<Delivery> deliveryForSession(String sessionId) {
        var session = SESSION_INSTANCES.get(sessionId);
        if (session != null) {
            return Optional.of(new Delivery(session.resource, session.broadcastToRoom));
        }
        var placed = sessionId != null ? PLACED.get(sessionId) : null;
        return placed != null ? Optional.of(placed.delivery) : Optional.empty();
    }

    /**
     * Run {@code broadcast}, which sends a frame of session {@code sessionId} to
     * {@code delivery}'s recipients, with the session known to
     * {@link #deliveryForSession} as one whose frames go there, so a filter that
     * defers that frame once more still finds its recipients when the session
     * has ended meanwhile. Only for the time the broadcaster filters the frame,
     * so nothing outlives the call.
     *
     * @param sessionId the session the frame belongs to
     * @param delivery  where the session's frames go
     * @param broadcast the broadcast of the frame
     */
    public static void broadcastPlaced(String sessionId, Delivery delivery, Runnable broadcast) {
        var placement = new Placement(delivery);
        PLACED.put(sessionId, placement);
        try {
            broadcast.run();
        } finally {
            PLACED.remove(sessionId, placement);
        }
    }

    /**
     * Keep {@code message}, a frame a broadcast filter defers past the frame it is
     * filtering, in the reply of a long-polling session that is buffering that
     * frame on this thread, after the frame itself.
     *
     * @return {@code false} when no long-polling session of {@code sessionId} is
     * buffering a frame on this thread; the caller then delivers the frame itself
     */
    public static boolean deferForLongPolling(String sessionId, RawMessage message) {
        var session = sessionId != null ? SESSION_INSTANCES.get(sessionId) : null;
        if (session == null || session.longPolling == null || message == null || message.message() == null
                || !Thread.holdsLock(session.longPollingLock)) {
            return false;
        }
        session.deferredFrames.add(message.message().toString());
        return true;
    }

    /**
     * Run {@code broadcast}, a topic session's terminal broadcast, with the
     * session known to {@link #deliveryForSession} as one whose frames go to every
     * subscriber. Only the terminal frame is registered, and only for the time
     * the broadcaster filters it, so nothing outlives the call.
     */
    static void broadcastTopicTerminal(String sessionId, Runnable broadcast) {
        broadcastPlaced(sessionId, new Delivery(null, true), broadcast);
    }

    /**
     * Remove all sessions associated with a disconnecting resource.
     * Called from {@code AiEndpointHandler} when a client disconnects
     * before the streaming session completes.
     */
    public static void cleanupResource(AtmosphereResource resource) {
        cleanupResource(resource.uuid());
    }

    /**
     * Remove all sessions associated with a disconnecting resource by its
     * UUID. Used by the recycled-disconnect path where the container has
     * already stripped the {@link AtmosphereResource} from the event but the
     * event still carries the UUID it was created with.
     *
     * <p>A session that broadcasts to the room ({@link StreamingSessions#startRoomBroadcast})
     * is left open: its frames go to every member of the room, not to the
     * departing resource that opened it, so the members still present keep
     * receiving the reply. It belongs to whoever started it (for an
     * {@code @AiEndpoint(broadcastReply = true)} turn, the run), and is closed
     * by its own terminal frame ({@code complete}, {@code error}, or the
     * {@code cancelled} frame of a run cut short, which a prompter that left
     * or whose reattach grace expired gets, its {@code @Prompt} method
     * returned or not), or, when none comes, by the idle sweep
     * ({@link #sweepExpired}). A room destroyed meanwhile is not recreated by
     * it: its frames go to a room created again under the same id, and are
     * dropped while there is none.</p>
     *
     * @param resourceUuid the atmosphere resource UUID (may be null; no-op)
     */
    public static void cleanupResource(String resourceUuid) {
        if (resourceUuid == null) {
            return;
        }
        for (var entry : SESSION_INSTANCES.entrySet()) {
            var session = entry.getValue();
            // Deregister only by winning the close, as sweepExpired does: a
            // session that already closed is broadcasting its terminal frame and
            // deregisters itself after it, so a filter deferring a frame past
            // that one still reads where the session's frames go.
            if (!session.broadcastToRoom && resourceUuid.equals(session.resource.uuid())
                    && session.closed.compareAndSet(false, true)) {
                SESSION_INSTANCES.remove(entry.getKey(), session);
                SESSION_RESOURCES.remove(entry.getKey(), session.resource);
                session.closeLongPolling();
            }
        }
    }

    /**
     * Remove sessions whose last outbound activity is older than
     * {@code ttlMs}. Invoked by {@link StreamingSessionSweeper} so orphaned
     * sessions — a runtime that never fires a terminal event, a disconnect
     * the container never delivered — cannot grow the process-global maps
     * without bound (Correctness Invariant #3). Reaped sessions are marked
     * closed so late writes from a stuck runtime are dropped at the wire
     * layer instead of resurrecting the entry.
     *
     * @param ttlMs the idle TTL in milliseconds
     * @return the number of sessions removed
     */
    static int sweepExpired(long ttlMs) {
        var cutoff = System.currentTimeMillis() - ttlMs;
        var removed = 0;
        for (var entry : SESSION_INSTANCES.entrySet()) {
            var session = entry.getValue();
            // Reap only by winning the close: a session closing on its own is
            // broadcasting its terminal frame and deregisters itself after it.
            if (session.lastActivityMillis < cutoff
                    && session.closed.compareAndSet(false, true)) {
                SESSION_INSTANCES.remove(entry.getKey(), session);
                SESSION_RESOURCES.remove(entry.getKey());
                session.closeLongPolling();
                removed++;
                logger.debug("Swept idle streaming session {}", entry.getKey());
            }
        }
        return removed;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void send(String text) {
        if (closed.get()) {
            logger.warn("Attempted to send streaming text on closed session {}", sessionId);
            return;
        }
        broadcast(buildMessage("streaming-text", text));
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if (closed.get()) {
            // DEBUG, not WARN: legitimate late metadata exists on disconnect
            // paths, but a silent drop hid the interceptor-after-complete bug.
            logger.debug("Dropping metadata '{}' on closed session {}", key, sessionId);
            return;
        }
        var msg = new LinkedHashMap<String, Object>();
        msg.put("type", "metadata");
        msg.put("key", key);
        msg.put("value", value);
        msg.put("sessionId", sessionId);
        msg.put("seq", sequence.incrementAndGet());
        broadcast(toJson(msg));
        if (longPolling != null && org.atmosphere.ai.resume.RunReattachSupport.RUN_ID_HEADER.equals(key)) {
            // The client needs its run id before the reply ends: one that loses its
            // connection mid-reply (a page reload) comes back for the run with it.
            flushLongPolling();
        }
    }

    @Override
    public void progress(String message) {
        if (closed.get()) {
            return;
        }
        broadcast(buildMessage("progress", message));
    }

    @Override
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            broadcastTerminal(buildMessage("complete", null));
        }
    }

    @Override
    public void complete(String summary) {
        if (closed.compareAndSet(false, true)) {
            broadcastTerminal(buildMessage("complete", summary));
        }
    }

    @Override
    public void error(Throwable t) {
        if (closed.compareAndSet(false, true)) {
            // Only an error that ends the session marks it errored: one that
            // comes after its end (a complete that won the close) did not.
            errored.set(true);
            if (t instanceof RunCancelledException cancelled) {
                // A cut-short run is not a failure: its one terminal frame is
                // the cancelled frame naming why.
                logger.debug("Streaming session {} cancelled: {}", sessionId, cancelled.reason().wire());
                broadcastTerminal(buildMessage(RunCancelledException.FRAME_TYPE, cancelled.reason().wire()));
                return;
            }
            if (t instanceof RequestDeniedException denied) {
                // A policy or guardrail refused the turn: an expected outcome the
                // denying code already logged, so one line here and no stack. The
                // client still gets the same error frame as any other failure.
                logger.info("Streaming session {} denied: {}", sessionId, denied.getMessage());
            } else {
                logger.error("Streaming session {} error", sessionId, t);
            }
            var message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            broadcastTerminal(buildMessage("error", message));
        }
    }

    @Override
    public boolean hasErrored() {
        return errored.get();
    }

    @Override
    public void close() {
        complete();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void emit(AiEvent event) {
        // Terminal events must transition the closed state
        switch (event) {
            case AiEvent.Complete c -> {
                if (closed.compareAndSet(false, true)) {
                    broadcastTerminal(buildEventMessage(event));
                }
                return;
            }
            case AiEvent.Error err -> {
                if (closed.compareAndSet(false, true)) {
                    logger.error("Streaming session {} error: {}", sessionId, err.message());
                    broadcastTerminal(buildMessage("error", err.message()));
                }
                return;
            }
            default -> {
                if (closed.get()) {
                    logger.warn("Attempted to emit event on closed session {}", sessionId);
                    return;
                }
                broadcast(buildEventMessage(event));
                if (longPolling != null && event instanceof AiEvent.ApprovalRequired) {
                    // The run waits for the client's answer: hand the reply so far over now.
                    flushLongPolling();
                }
            }
        }
    }

    @Override
    public void sendContent(Content content) {
        if (closed.get()) {
            return;
        }
        switch (content) {
            case Content.Text text -> send(text.text());
            case Content.Image image -> {
                var msg = new LinkedHashMap<String, Object>();
                msg.put("type", "content");
                msg.put("contentType", "image");
                msg.put("mimeType", image.mimeType());
                msg.put("data", image.dataBase64());
                msg.put("sessionId", sessionId);
                msg.put("seq", sequence.incrementAndGet());
                broadcast(toJson(msg));
            }
            case Content.File file -> {
                var msg = new LinkedHashMap<String, Object>();
                msg.put("type", "content");
                msg.put("contentType", "file");
                msg.put("mimeType", file.mimeType());
                msg.put("fileName", file.fileName());
                msg.put("data", file.dataBase64());
                msg.put("sessionId", sessionId);
                msg.put("seq", sequence.incrementAndGet());
                broadcast(toJson(msg));
            }
            case Content.Audio audio -> {
                // Phase 4: audio variant for multi-modal parts.
                var msg = new LinkedHashMap<String, Object>();
                msg.put("type", "content");
                msg.put("contentType", "audio");
                msg.put("mimeType", audio.mimeType());
                msg.put("data", audio.dataBase64());
                msg.put("sessionId", sessionId);
                msg.put("seq", sequence.incrementAndGet());
                broadcast(toJson(msg));
            }
        }
    }

    /**
     * Echo a room member's prompt to the room this session broadcasts to, as the
     * {@code prompt} frame
     * {@code {"type":"prompt","text":<prompt>,"from":<member>,"sessionId":..,"seq":n}}:
     * the prompt is in {@code text}, not {@code data}, so a client that does not
     * know the type does not render it as reply text. It runs through the room's
     * broadcast filters like every frame of the session. A session that does not
     * broadcast to a room sends nothing: a prompt is echoed to the room only.
     *
     * @param text the prompt
     * @param from the room member id ({@link RoomMemberIds}) of the member who sent it
     */
    public void sendPrompt(String text, String from) {
        if (!broadcastToRoom) {
            logger.debug("Not echoing a prompt on session {}: it does not broadcast to a room", sessionId);
            return;
        }
        if (closed.get()) {
            logger.debug("Dropping the prompt echo of closed session {}", sessionId);
            return;
        }
        broadcast(org.atmosphere.ai.filter.AiStreamMessage.prompt(text, from, sessionId,
                sequence.incrementAndGet()).toJson());
    }

    String resourceUuid() {
        return resource.uuid();
    }

    private String buildEventMessage(AiEvent event) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("event", event.eventType());
        msg.put("data", event);
        msg.put("sessionId", sessionId);
        msg.put("seq", sequence.incrementAndGet());
        return toJson(msg);
    }

    private String buildMessage(String type, String data) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("type", type);
        if (data != null) {
            msg.put("data", data);
        }
        msg.put("sessionId", sessionId);
        msg.put("seq", sequence.incrementAndGet());
        return toJson(msg);
    }

    /**
     * Broadcast the session's terminal frame, then deregister the session. The
     * broadcaster runs its filters inside {@code broadcast}, so a filter that
     * defers a frame of its own past this one ({@link #deliveryForSession})
     * still finds where the session's frames go.
     */
    private void broadcastTerminal(String json) {
        try {
            broadcast(json);
            if (longPolling != null) {
                flushLongPolling();
            }
        } finally {
            closeLongPolling();
            SESSION_RESOURCES.remove(sessionId);
            SESSION_INSTANCES.remove(sessionId);
        }
    }

    /**
     * The room this session broadcasts to: the one it started with while it
     * lives, else the room live under the same id (destroyed by an empty- or
     * idle-destroy lifecycle policy and created again by a member joining),
     * looked up without creating one; {@code null} when there is none.
     */
    private Broadcaster liveRoom() {
        var current = room;
        if (current == null || !current.isDestroyed()) {
            return current;
        }
        Broadcaster replacement;
        try {
            var factory = resource.getAtmosphereConfig().getBroadcasterFactory();
            replacement = factory != null ? factory.lookup(current.getID(), false) : null;
        } catch (RuntimeException e) {
            logger.debug("Unable to look up the room {} of session {}", current.getID(), sessionId, e);
            return null;
        }
        if (replacement == null || replacement.isDestroyed()) {
            return null;
        }
        room = replacement;
        installRoom(replacement, resource);
        return replacement;
    }

    private static Broadcaster roomOf(AtmosphereResource resource) {
        try {
            return resource.getBroadcaster();
        } catch (RuntimeException e) {
            logger.debug("Unable to read the room of {}", resource.uuid(), e);
            return null;
        }
    }

    private static void installRoom(Broadcaster room, AtmosphereResource resource) {
        try {
            LongPollingRoomReplies.install(room);
        } catch (RuntimeException e) {
            logger.debug("Unable to install the long-polling replies of the room of {}", resource.uuid(), e);
        }
    }

    private static boolean isLongPolling(AtmosphereResource resource) {
        try {
            return resource.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING;
        } catch (RuntimeException e) {
            logger.trace("Unable to read the transport of {}", resource, e);
            return false;
        }
    }

    /** The identity the connection was suspended under, or {@code null} for none or an unreadable request. */
    static Object connectionOwner(AtmosphereResource resource) {
        try {
            var request = resource.getRequest();
            return request != null ? request.getAttribute(LongPollingReplies.CONNECTION_OWNER_ATTRIBUTE) : null;
        } catch (RuntimeException e) {
            // Read as anonymous: a poll carrying an authenticated principal is then never handed the reply.
            logger.trace("Unable to read the owner of {}", resource.uuid(), e);
            return null;
        }
    }

    /**
     * Run {@code json} through the broadcaster's filters, as a broadcast would,
     * and keep what they let through, followed by the frames they deferred past
     * it, in the long-polling reply. A frame that takes the reply past its
     * bounds ends the session with an error frame in place of its frames.
     */
    private void bufferForLongPolling(String json) {
        synchronized (longPollingLock) {
            if (longPolling.isClosed()) {
                logger.debug("Dropping a frame of session {}: its long-polling reply was closed", sessionId);
                return;
            }
            List<BroadcastFilter> filters;
            try {
                filters = broadcastFilters();
            } catch (RuntimeException e) {
                // Fail closed, as the streaming path does when broadcast throws:
                // a frame its filters cannot see is never delivered.
                logger.warn("Unable to read the broadcast filters of session {}; ending its long-polling reply",
                        sessionId, e);
                failLongPolling("The reply could not be filtered for delivery over long-polling");
                return;
            }
            var broadcasterId = broadcasterId();
            var pending = new ArrayDeque<String>();
            pending.add(json);
            var rounds = 0;
            while (!pending.isEmpty()) {
                var filtered = org.atmosphere.ai.resume.RunReattachSupport.applyFilters(
                        filters, broadcasterId, pending.poll());
                if (filtered != null && !longPolling.add(filtered)) {
                    deferredFrames.clear();
                    logger.warn("The long-polling reply of session {} exceeds {} bytes, {} frames or the {} bytes"
                                    + " all long-polling replies may hold; ending it with an error frame", sessionId,
                            longPolling.limits().maxReplyBytes(), longPolling.limits().maxReplyFrames(),
                            longPolling.limits().maxBufferedBytes());
                    failLongPolling("The reply is too large to be delivered over long-polling");
                    return;
                }
                if (!deferredFrames.isEmpty()) {
                    if (++rounds > MAX_DEFERRAL_ROUNDS) {
                        logger.warn("Filters of {} kept deferring frames of session {}; dropping {} of them",
                                broadcasterId, sessionId, deferredFrames.size());
                    } else {
                        pending.addAll(deferredFrames);
                    }
                    deferredFrames.clear();
                }
            }
            // The run event this frame stands for, if any: the client that
            // receives the reply was handed the run up to it.
            var position = org.atmosphere.ai.resume.RunEventCapturingSession.positionOfFrameBeingWritten(this);
            if (position != null) {
                longPolling.reached(position);
            }
        }
    }

    /** Hand the frames buffered so far over to the client's next poll. */
    private void flushLongPolling() {
        synchronized (longPollingLock) {
            if (longPolling.isClosed()) {
                return;
            }
            LongPollingReplies.park(resource, longPolling.trackingId(), longPollingOwner,
                    longPolling.drain(), longPolling.limits());
            if (longPolling.isClosed()) {
                // Cleaned up while handing over: the client is gone, so is its reply.
                LongPollingReplies.discard(longPolling.trackingId());
            }
        }
    }

    /**
     * The reply cannot be delivered (it outgrew a bound, or its frames cannot be
     * filtered): drop its frames, end the session, and hand the client an error
     * frame carrying {@code clientMessage} in their place, so it is told rather
     * than left with a reply that never completes.
     */
    private void failLongPolling(String clientMessage) {
        if (longPolling.isClosed()) {
            // Cleaned up or reaped meanwhile: nobody is left to tell.
            return;
        }
        closed.set(true);
        errored.set(true);
        longPolling.discardFrames();
        var error = buildMessage("error", clientMessage);
        LongPollingReplies.park(resource, longPolling.trackingId(), longPollingOwner,
                new LongPollingReplies.Batch(List.of(error), 0), longPolling.limits());
        closeLongPolling();
        SESSION_RESOURCES.remove(sessionId);
        SESSION_INSTANCES.remove(sessionId);
    }

    private void closeLongPolling() {
        if (longPolling != null) {
            longPolling.close();
        }
    }

    /**
     * The broadcaster's filters a unicast frame runs through, cluster filters
     * excepted (a broadcast to chosen resources skips them too); throws when the
     * broadcaster cannot be read.
     */
    private List<BroadcastFilter> broadcastFilters() {
        var broadcaster = resource.getBroadcaster();
        if (broadcaster == null) {
            throw new IllegalStateException("no broadcaster");
        }
        var filters = new ArrayList<BroadcastFilter>();
        for (var filter : broadcaster.getBroadcasterConfig().filters()) {
            if (!(filter instanceof ClusterBroadcastFilter)) {
                filters.add(filter);
            }
        }
        return filters;
    }

    private String broadcasterId() {
        try {
            var broadcaster = resource.getBroadcaster();
            return broadcaster != null ? broadcaster.getID() : sessionId;
        } catch (RuntimeException e) {
            logger.trace("Unable to read the broadcaster of session {}", sessionId, e);
            return sessionId;
        }
    }

    private void broadcast(String json) {
        // Every outbound frame refreshes the TTL clock so the sweeper never
        // reaps a session that is actively streaming.
        lastActivityMillis = System.currentTimeMillis();
        if (longPolling != null) {
            bufferForLongPolling(json);
            return;
        }
        // Wrap in RawMessage so ManagedAtmosphereHandler.onStateChange()
        // delivers the JSON as-is without re-invoking @Message handlers.
        try {
            if (broadcastToRoom) {
                // Room fan-out (@AiEndpoint.broadcastReply=true): deliver the
                // single reply to every subscriber on the per-path broadcaster,
                // not just the originating resource. The prompt was dispatched
                // to one @Prompt handler upstream, so the model ran exactly once.
                // A frame of a run carries the place of its run event in the run,
                // for the long-polling members the room keeps it for.
                var target = liveRoom();
                if (target == null) {
                    // The room is gone (destroyed once empty) and none was
                    // created again under its id: nobody is left to receive the
                    // frame, and asking the resource for its broadcaster would
                    // recreate the room under the same id.
                    logger.debug("Dropping a frame of room session {}: its room is gone", sessionId);
                    return;
                }
                var position = org.atmosphere.ai.resume.RunEventCapturingSession.positionOfFrameBeingWritten(this);
                target.broadcast(position != null
                        ? new LongPollingRoomReplies.RunFrame(json, position)
                        : new RawMessage(json));
            } else {
                // Default: deliver only to the originating resource (unicast)
                // while still passing through the broadcaster's filter/cache chain.
                resource.getBroadcaster().broadcast(new RawMessage(json), Set.of(resource));
            }
        } catch (Exception e) {
            logger.warn("Failed to broadcast from session {}: {}", sessionId, e.getMessage());
        }
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JacksonException e) {
            logger.error("Failed to serialize streaming message", e);
            return "{}";
        }
    }
}
