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
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catch-up of a member of a {@code broadcastReply} room that reconnects
 * with a {@link ResumePoint}: the frames of the room's runs written after what
 * it says the client last received, each once, in order, before anything else
 * the room writes it.
 *
 * <p>What the member missed is read from the runs' replay buffers: of a
 * session the resume point names, the frames numbered past its {@code seq};
 * of a run of the room it names none of, the frames captured from the moment
 * the client received the last of the frames it names. Those reach it whether
 * they were written into a connection whose loss the server had not noticed
 * yet, kept by the room's {@link org.atmosphere.cpr.BroadcasterCache} (which
 * holds what was broadcast once the loss was noticed, and drops the oldest of
 * it past its {@code maxPerClient}), or neither. Only a run of this room whose
 * frames every member received, and whose replay the caller may read (an
 * anonymous run, or one of the caller's own user), is read; a session id the
 * server does not know yields nothing. Text, prompt and terminal frames are
 * caught up; metadata, progress and tool-event frames, which no replay buffer
 * keeps, are not.</p>
 *
 * <p>The frames are written as the member joins the room: merged with what the
 * cache writes it then ({@link #mergeCached}), else before the first frame the
 * room writes it ({@link #beforeLive}), else once it joined ({@link #write}).
 * A frame of the catch-up is then not written again, neither by the cache nor
 * by the room (a frame captured before the member joined but broadcast after
 * it), nor one the client said it holds; and a reattach of the member to a
 * run of the room with the run id writes none of the run
 * ({@link #covers}).</p>
 *
 * <p>Bounded (Correctness Invariant #3): the {@value #MAX_RUNS} latest runs of
 * the room, each read from its replay buffer, itself bounded.</p>
 */
public final class RoomCatchUp {

    /** Request attribute holding the catch-up of a reconnecting room member. */
    public static final String ATTRIBUTE = "org.atmosphere.ai.roomCatchUp";

    /** The most runs of a room a catch-up reads. */
    static final int MAX_RUNS = 16;

    private static final Logger logger = LoggerFactory.getLogger(RoomCatchUp.class);
    private static final String SESSION_FIELD = "\"sessionId\":\"";
    private static final String SEQ_FIELD = "\"seq\":";

    private final AtmosphereResource resource;
    private final ResumePoint point;
    private final String roomId;
    private final RunRegistry registry;
    /** Guarded by {@code written}: whether the catch-up was written (or found empty). */
    private boolean done;
    /** Guarded by itself: session id -> the {@code seq}s of the frames the catch-up wrote. */
    private final Map<String, Set<Long>> written = new HashMap<>();

    private RoomCatchUp(AtmosphereResource resource, ResumePoint point, String roomId, RunRegistry registry) {
        this.resource = resource;
        this.point = point;
        this.roomId = roomId;
        this.registry = registry;
    }

    /**
     * Prepare the catch-up of {@code resource}, about to join the room
     * {@code roomId}, when its request carries a {@link ResumePoint}; a no-op
     * otherwise, which leaves the reconnect as it was without one.
     *
     * @param resource the reconnecting connection, not suspended yet
     * @param registry the registry holding the room's runs
     * @param roomId   the id of the room's broadcaster
     */
    public static void prepare(AtmosphereResource resource, RunRegistry registry, String roomId) {
        if (resource == null || registry == null || roomId == null) {
            return;
        }
        var point = ResumePoint.of(resource);
        if (point.isEmpty()) {
            return;
        }
        try {
            var catchUp = new RoomCatchUp(resource, point.get(), roomId, registry);
            resource.getRequest().setAttribute(ATTRIBUTE, catchUp);
            logger.debug("Prepared {}", catchUp);
        } catch (RuntimeException e) {
            logger.trace("Unable to prepare the catch-up of {}", resource.uuid(), e);
        }
    }

    /** The catch-up of {@code resource}, or {@code null} when it has none. */
    public static RoomCatchUp of(AtmosphereResource resource) {
        if (resource == null) {
            return null;
        }
        try {
            var request = resource.getRequest();
            return request != null && request.getAttribute(ATTRIBUTE) instanceof RoomCatchUp catchUp ? catchUp : null;
        } catch (RuntimeException e) {
            logger.trace("Unable to read the catch-up of {}", resource.uuid(), e);
            return null;
        }
    }

    /**
     * Whether {@code resource} catches up on the runs of room {@code roomId}
     * (it reconnected with a resume point to that room): a reattach of it to a
     * run of the room writes none of the run's events.
     */
    static boolean covers(AtmosphereResource resource, String roomId) {
        var catchUp = of(resource);
        return catchUp != null && roomId != null && roomId.equals(catchUp.roomId);
    }

    /** Write the catch-up now, unless it was written. */
    public void write() {
        synchronized (written) {
            if (done) {
                return;
            }
            done = true;
            // Under the lock: a frame the room writes meanwhile waits in beforeLive.
            writeFrames(frames(Set.of()));
        }
    }

    /**
     * Before the room writes {@code message} to the member: write the catch-up
     * first, unless it was written.
     *
     * @return whether {@code message} is to be written: not when it is a frame
     * the catch-up wrote, or one the client holds
     */
    public boolean beforeLive(Object message) {
        write();
        var json = message instanceof RawMessage raw ? raw.message() : message;
        return !(json instanceof String frame) || !alreadySent(frame);
    }

    /**
     * The messages to write the member instead of {@code cached}, what the
     * room's cache kept for it while it was away: the catch-up's frames the
     * cache does not hold, each before the cached frames of its session that
     * follow it, then the cached messages but the frames the client holds.
     * The catch-up counts as written.
     */
    public List<Object> mergeCached(List<?> cached) {
        synchronized (written) {
            var kept = new ArrayList<Object>();
            for (var message : cached) {
                var json = text(message);
                if (json == null || !alreadySent(json)) {
                    kept.add(message);
                }
            }
            if (done) {
                return kept;
            }
            done = true;
            var cachedKeys = new HashSet<String>();
            var cachedSessions = new HashSet<String>();
            for (var message : kept) {
                var key = key(text(message));
                if (key != null) {
                    cachedKeys.add(key.session() + '#' + key.seq());
                    cachedSessions.add(key.session());
                }
            }
            var frames = frames(cachedKeys);
            if (frames.isEmpty()) {
                return kept;
            }
            var merged = new ArrayList<Object>(frames.size() + kept.size());
            var pending = new ArrayList<String>();
            // A session the cache holds nothing of: its frames come first, in order.
            for (var frame : frames) {
                var key = key(frame);
                if (key != null && cachedSessions.contains(key.session())) {
                    pending.add(frame);
                } else {
                    merged.add(frame);
                }
            }
            for (var message : kept) {
                var key = key(text(message));
                if (key != null) {
                    var it = pending.iterator();
                    while (it.hasNext()) {
                        var frame = it.next();
                        var frameKey = key(frame);
                        if (frameKey != null && frameKey.session().equals(key.session())
                                && frameKey.seq() < key.seq()) {
                            merged.add(frame);
                            it.remove();
                        }
                    }
                }
                merged.add(message);
            }
            merged.addAll(pending);
            logger.info("Room {}: {} frame(s) caught up for {} with the {} the cache kept", roomId, frames.size(),
                    resource.uuid(), kept.size());
            return merged;
        }
    }

    /** Whether {@code json} is a frame the client holds or the catch-up wrote. Caller may hold no lock. */
    private boolean alreadySent(String json) {
        var key = key(json);
        if (key == null) {
            return false;
        }
        if (key.seq() <= point.lastSeq(key.session())) {
            return true;
        }
        synchronized (written) {
            var seqs = written.get(key.session());
            return seqs != null && seqs.contains(key.seq());
        }
    }

    /**
     * The frames of the room's runs the client missed, in the order they were
     * captured, but those whose {@code sessionId#seq} is in {@code skip}; each is
     * recorded as written. Caller holds the lock.
     */
    private List<String> frames(Set<String> skip) {
        var request = resource.getRequest();
        var runs = new ArrayList<AgentResumeHandle>();
        for (var run : registry.replayable()) {
            var buffer = run.replayBuffer();
            if (roomId.equals(buffer.roomId()) && buffer.frameSessionId() != null
                    && request != null && RunReattachSupport.callerOwnsRun(request, run.userId())) {
                runs.add(run);
            }
        }
        // The moment the client received the last frame it names: a run of the
        // room it names nothing of is caught up from then.
        Instant since = null;
        for (var run : runs) {
            var buffer = run.replayBuffer();
            var last = point.lastSeq(buffer.frameSessionId());
            if (last < 0) {
                continue;
            }
            for (var event : buffer.snapshot()) {
                var seq = buffer.frameOf(event);
                if (seq >= 0 && seq <= last && (since == null || event.timestamp().isAfter(since))) {
                    since = event.timestamp();
                }
            }
        }
        record Missed(AgentResumeHandle run, RunEvent event) {
        }
        var missed = new ArrayList<Missed>();
        var latest = runs.stream()
                .sorted(Comparator.comparing((AgentResumeHandle run) -> firstCapture(run)).reversed())
                .limit(MAX_RUNS).toList();
        for (var run : latest) {
            var buffer = run.replayBuffer();
            var last = point.lastSeq(buffer.frameSessionId());
            if (last < 0 && since == null) {
                continue;
            }
            for (var event : buffer.snapshot()) {
                var seq = buffer.frameOf(event);
                if (seq < 0 || skip.contains(buffer.frameSessionId() + '#' + seq)) {
                    continue;
                }
                if (last >= 0 ? seq > last : !event.timestamp().isBefore(since)) {
                    missed.add(new Missed(run, event));
                }
            }
        }
        logger.debug("{}: {} run(s) of the room, caught up since {}: {} event(s) missed", this, runs.size(), since,
                missed.size());
        missed.sort(Comparator.comparing((Missed m) -> m.event().timestamp())
                .thenComparing(m -> m.run().runId()).thenComparingLong(m -> m.event().sequence()));
        var filters = RunReattachSupport.filtersFor(resource);
        var broadcasterId = RunReattachSupport.broadcasterIdFor(resource);
        var frames = new ArrayList<String>(missed.size());
        for (var m : missed) {
            var message = RunReattachSupport.replayFrame(m.event(), m.run(), m.run().runId(), 0);
            var json = RunReattachSupport.applyFilters(filters, broadcasterId, message.toJson());
            if (json != null) {
                frames.add(json);
                written.computeIfAbsent(message.sessionId(), s -> new HashSet<>()).add(message.seq());
            }
        }
        return frames;
    }

    /** When the first retained event of {@code run} was captured, {@link Instant#MIN} when none is. */
    private static Instant firstCapture(AgentResumeHandle run) {
        var events = run.replayBuffer().snapshot();
        return events.isEmpty() ? Instant.MIN : events.get(0).timestamp();
    }

    /** Write {@code frames} to the member, each alone, as the room writes its frames. Caller holds the lock. */
    private void writeFrames(List<String> frames) {
        if (frames.isEmpty()) {
            return;
        }
        try {
            var writer = resource.getResponse().getWriter();
            for (var frame : frames) {
                writer.write(frame);
                writer.flush();
            }
            logger.info("Room {}: {} frame(s) caught up for {}", roomId, frames.size(), resource.uuid());
        } catch (java.io.IOException | RuntimeException e) {
            logger.warn("Catching {} up on room {} failed: {}", resource.uuid(), roomId, e.getMessage());
        }
    }

    /** The text of a message the room writes, or {@code null} when it is none. */
    private static String text(Object message) {
        var body = message instanceof RawMessage raw ? raw.message() : message;
        return body instanceof String json ? json : null;
    }

    /** The session and {@code seq} of an AI frame. */
    private record Key(String session, long seq) {
    }

    /** The session and {@code seq} of the AI frame {@code json}, or {@code null} when it names none. */
    private static Key key(String json) {
        if (json == null) {
            return null;
        }
        // The last ones: a frame's own fields follow its data, which may nest objects.
        var s = json.lastIndexOf(SESSION_FIELD);
        var q = json.lastIndexOf(SEQ_FIELD);
        if (s < 0 || q < 0) {
            return null;
        }
        var start = s + SESSION_FIELD.length();
        var end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        var digits = q + SEQ_FIELD.length();
        var stop = digits;
        while (stop < json.length() && stop - digits < 19 && Character.isDigit(json.charAt(stop))) {
            stop++;
        }
        if (stop == digits) {
            return null;
        }
        return new Key(json.substring(start, end), Long.parseLong(json.substring(digits, stop)));
    }

    @Override
    public String toString() {
        return "RoomCatchUp{" + resource.uuid() + " in " + roomId + ", " + new LinkedHashMap<>(point.sessions()) + '}';
    }
}
