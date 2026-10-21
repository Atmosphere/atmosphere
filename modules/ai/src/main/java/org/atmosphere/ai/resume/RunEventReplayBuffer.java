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

import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded in-memory ring buffer of recent {@link RunEvent}s for a single
 * in-flight run. When a client disconnects and reconnects with the same
 * {@code runId}, the buffer replays the events it missed.
 *
 * <h2>Backpressure</h2>
 *
 * A run retains at most {@link #capacity()} events and
 * {@link #DEFAULT_MAX_CHARS} characters of payload (Correctness Invariant
 * #3 — every buffer declares a size bound). Past the events, its
 * consecutive text events are joined into one, which keeps each one's
 * sequence, frame, capture time and where its text ends, so a reply of
 * thousands of text deltas stays whole in a few events; every read
 * ({@link #snapshot}, {@link #subscribe}, {@link #claimNew}) hands them back
 * one by one, as they were captured. Still past either, the oldest
 * events but the run's prompt are dropped; a run whose frames are numbered
 * keeps in their place a text event saying so ({@link #TRUNCATED_NOTICE}),
 * which a client that missed them is replayed first. A buffer whose frames
 * are not numbered (no frame session) drops its oldest events silently, and
 * its consumers MUST treat missing earlier sequence numbers as "history
 * unavailable".
 *
 * <h2>Durability</h2>
 *
 * A buffer may optionally mirror every captured event to a
 * {@link RunJournal} via {@link #attachJournal}. When a journal is attached
 * the registry persists captures so a fresh process can rehydrate the run's
 * events after a crash. Journal writes are best-effort (Correctness
 * Invariant #3) and never thrown into the live stream: the first failure is
 * logged at WARN — durability silently turning itself off is an operator
 * incident — and repeats at TRACE. By default the journal is
 * {@link RunJournal#NOOP} and capture adds zero extra work.
 *
 * <h2>Live subscribers</h2>
 *
 * A reconnecting client needs the events it missed <em>and</em> the rest of
 * the run, which keeps writing into the original, now-closed session. "The
 * run" is what {@link RunEventCapturingSession} captures: the text and the
 * terminal frame the client received, whether the {@code @Prompt} method sent
 * them or the runtime streamed them for {@code session.stream(...)}.
 * {@link #subscribe} hands back the retained backlog and registers a
 * listener for every later capture in one step under the buffer lock, so
 * nothing falls between the backlog and the first live event and nothing is
 * delivered twice. Listeners are notified outside the lock, are dropped when
 * they throw, and are released once a terminal {@code complete} /
 * {@code error} / {@code cancelled} event is captured. A run that ends without capturing one (its
 * execution threw an {@link Error}, timed out after its client left, or was
 * removed from the {@link RunRegistry}) is closed by {@link #terminate},
 * which the registry calls on each of those paths, so every live listener
 * receives a terminal frame and is released (Correctness Invariant #2). An
 * {@code @AiEndpoint} run whose {@code @Prompt} method returned with its
 * session still open is not ended then: it ends with the terminal frame its
 * session sends ({@link #whenTerminated}). At most {@link #MAX_SUBSCRIBERS} are held per run
 * (Correctness Invariant #3).
 *
 * <p>A long-polling poll waiting for the run's next event listens through
 * {@link #awaitNext} instead, apart from those subscribers: it takes none of
 * their places, so polls waiting on a run never stop a WebSocket, SSE or
 * streaming reattach from receiving the rest of it. At most one poll per
 * consumer (a tracking id) waits, the newer taking over from the older, and at
 * most {@link #maxWaitingPolls()} consumers' polls per run (Correctness
 * Invariant #3); waiting polls are notified and released like the subscribers.</p>
 *
 * <h2>Replay positions</h2>
 *
 * A long-polling client gets the run in pieces, one per poll, and re-sends the
 * run id on each one until it reads a terminal frame. {@link #claimNew} and
 * {@link #claim} hand each event to such a consumer once: the buffer remembers,
 * per consumer (a tracking id), the sequence of the last event it handed over,
 * for at most {@link #maxReplayPositions()} consumers per run, the least
 * recently used forgotten first, and forgets a consumer gone for good
 * ({@link #forget}: it left its room or closed its connection on purpose, or no
 * poll of it was seen for its time to live). A forgotten consumer is handed the
 * retained events again. The frames a client received through a long-polling poll as a
 * reply ({@code org.atmosphere.ai.LongPollingReplies}) count as handed to it
 * too ({@link #handed}), so a later poll of it carrying the run id is not
 * handed them again, as long as they follow on from what it was handed before
 * without a gap: frames that do not count as handed to it (a reply dropped or
 * expired before a poll took it, a room frame not kept for it, which reached it
 * only if a poll of it waited) and every frame after them are handed to it
 * again, so it may receive some of these twice, but loses none. What a consumer
 * received that way takes none of the places of the consumers {@link #claimNew}
 * or {@link #claim} handed events to, so the members of a room receiving a reply
 * never make the run forget one of those: the run remembers it for at most
 * {@link #maxReplayPositions()} other consumers, the least recently served
 * forgotten first, and what a forgotten consumer received does not count as
 * handed to it: a claim hands it the retained events again, and it then takes a
 * place like any consumer a claim handed events to.
 *
 * <h2>Sizing</h2>
 *
 * A remembered consumer costs about one map entry: its tracking id (a
 * {@code String} of up to 128 characters, 36 for the UUID atmosphere.js sends,
 * about 80 bytes), a boxed sequence (16 bytes) and the entry of an ordered map
 * (about 40 bytes, plus its slot), some 150 bytes. A consumer is in one of the
 * two maps at most, but each map holds up to the bound
 * ({@link #maxReplayPositions()}), so a run remembers up to twice the bound in
 * all: the consumers a claim handed events to, and as many others whose frames
 * received as a long-polling reply it records. The tracking ids whose reattach
 * was refused for not owning the run ({@link #firstRefusal}), up to the bound
 * as well, cost about 120 bytes each. The figures below are that worst case of
 * the two maps. The registry keeps the buffers of its live runs and of up to
 * {@link RunRegistry#MAX_RETAINED_COMPLETED} (256) finished ones.
 *
 * <p>The events themselves: at most {@link #DEFAULT_MAX_CHARS} characters per
 * run, payload and bookkeeping (28 bytes per event or joined text delta,
 * counted as 14 characters) together, so at most 512 KB of {@code char}s per
 * run (less with compact strings, as text in Latin-1 is), 128 MB if each of
 * the 256 finished runs kept had replied that much; a chat reply of 2,000 text
 * deltas, some 8,000 characters, takes about 72 KB. A read hands the joined
 * deltas back one by one, at most some 17,000 of them (each at least one
 * character and its bookkeeping). Before the character bound, 1,024 events of
 * any size were retained.</p>
 *
 * <ul>
 *   <li>A run whose reply reaches its own client only ({@link #forRun}) is
 *       handed its id by that client alone: its consumers are that tracking id
 *       and the few a reloaded page gives it, so it remembers
 *       {@value #MAX_REPLAY_POSITIONS} by default in each map, some 4.8 KB, about
 *       1.2 MB for 256 finished runs.</li>
 *   <li>Every member of a {@code broadcastReply} room is handed the run id with
 *       the room's reply ({@link #forRoomRun}), and its polls carry it. Such a
 *       run remembers as many consumers as the room takes long-polling members
 *       ({@code LongPollingRoomReplies.memberLimit}, the init parameter
 *       {@code org.atmosphere.ai.longPolling.maxParkedReplies}, 1,024 by
 *       default) in each map, so a full room never pushes out its own members:
 *       about 300 KB per run with 1,024 members present, some 77 MB if each of
 *       the 256 finished runs kept had a full room. A member that leaves for good frees
 *       its place, so the bound counts the members present at once, not every
 *       client that ever polled. Clients past the room's members (a long-polling
 *       client the full room did not take, a member that lost its connection and
 *       did not come back under its tracking id) are forgotten, least recently
 *       served first.</li>
 *   <li>Only the run's own client waits for the run's next event
 *       ({@code RunReattachSupport}), so {@value #MAX_WAITING_POLLS} waiting polls
 *       are enough for either kind of run.</li>
 * </ul>
 *
 * <p>{@link #forRun} and {@link #forRoomRun} read the bounds from the init
 * parameters {@value #MAX_REPLAY_POSITIONS_PARAM} and
 * {@value #MAX_WAITING_POLLS_PARAM} of the run's application, which override
 * these defaults. The runs an AI endpoint ({@code @AiEndpoint}, {@code @Agent},
 * {@code @Coordinator}) starts get their buffer from them; the other
 * constructors, and so the buffers of runs {@link RunRegistry#rehydrate()}
 * rebuilds, keep the defaults of a run that reaches its own client only.</p>
 */
public final class RunEventReplayBuffer {

    private static final Logger logger = LoggerFactory.getLogger(RunEventReplayBuffer.class);

    /**
     * Default capacity, in events. Consecutive text events are joined into one
     * when the run holds more ({@link #capture}), so a reply of any number of
     * text deltas takes a few events.
     */
    public static final int DEFAULT_CAPACITY = 1_024;

    /**
     * Default upper bound on the characters of the payloads one run retains:
     * 262,144 (512 KB of {@code char}s), some thirty times a long chat reply;
     * each event, and each text event joined into another, counts fourteen
     * more for its bookkeeping.
     * Past it, or past the capacity once the text events are joined, the
     * oldest events but the run's prompt are dropped, and a replay says so
     * ({@link #TRUNCATED_NOTICE}).
     */
    public static final int DEFAULT_MAX_CHARS = 262_144;

    /**
     * The text a replay writes in place of the events a run dropped to stay
     * within its bounds, under the {@code seq} of the last frame dropped: a
     * client that missed them sees that the reply is not whole, and one that
     * holds them drops it, as any frame it holds.
     */
    public static final String TRUNCATED_NOTICE =
            "*[The earlier part of this reply is no longer available to replay.]*\n\n";

    /** The event type of the text a client receives ({@code RunEventCapturingSession}). */
    private static final String TEXT_TYPE = "streaming-text";

    /** Upper bound on live subscribers per run — one per reattached connection. */
    public static final int MAX_SUBSCRIBERS = 8;

    /**
     * Default upper bound on the consumers whose replay position a run whose
     * reply reaches its own client only remembers; see
     * {@link #MAX_REPLAY_POSITIONS_PARAM}. Such a run's id is handed to that
     * client alone: its consumers are its tracking id and the few a reloaded page
     * gives it. A run broadcast to a room ({@link #forRoomRun}) is sized from the
     * room's member limit instead.
     */
    public static final int MAX_REPLAY_POSITIONS = 16;

    /**
     * Default upper bound on the consumers whose long-polling poll waits for one
     * run's next event; see {@link #MAX_WAITING_POLLS_PARAM}. Only the run's own
     * client waits ({@code RunReattachSupport}): the poll of another member of a
     * room run never does, so a room run needs no more of them than a run that
     * reaches its own client only.
     */
    public static final int MAX_WAITING_POLLS = 16;

    /**
     * Init parameter: the consumers (long-polling tracking ids) whose replay
     * position one run remembers, and, apart from them, those whose frames
     * received as a long-polling reply it remembers ({@link #handed}); a
     * positive integer. When set, it overrides both defaults:
     * {@value #MAX_REPLAY_POSITIONS} for a run that reaches its own client only,
     * the room's member limit for a run broadcast to a room ({@link #forRoomRun}).
     */
    public static final String MAX_REPLAY_POSITIONS_PARAM = "org.atmosphere.ai.longPolling.maxReplayPositionsPerRun";

    /**
     * Init parameter: the consumers (long-polling tracking ids) whose poll may
     * wait for one run's next event at once; a positive integer,
     * {@value #MAX_WAITING_POLLS} by default.
     */
    public static final String MAX_WAITING_POLLS_PARAM = "org.atmosphere.ai.longPolling.maxWaitingPollsPerRun";

    /**
     * Where a run event is in its run: the frame being written stands for the
     * event {@code sequence} of {@code run}.
     *
     * @param run      the run's buffer
     * @param sequence the event's sequence
     */
    public record Position(RunEventReplayBuffer run, long sequence) {

        public Position {
            Objects.requireNonNull(run, "run");
        }
    }

    /**
     * Run events {@code first} to {@code last} of a run, every one of whose
     * frames a client received.
     *
     * @param run   the run's buffer
     * @param first the sequence of the first event
     * @param last  the sequence of the last event, not below {@code first}
     */
    public record Span(RunEventReplayBuffer run, long first, long last) {

        public Span {
            Objects.requireNonNull(run, "run");
            if (last < first) {
                throw new IllegalArgumentException("last " + last + " < first " + first);
            }
        }

        /** {@code consumer} received the frames of this span; see {@link RunEventReplayBuffer#handed}. */
        public void reachedBy(String consumer) {
            run.handed(consumer, first, last);
        }
    }

    /**
     * The backlog a {@link #subscribe} call returned, whether later captures
     * will reach the listener, and the handle that stops them.
     *
     * @param backlog every retained event at subscription time, oldest first
     * @param live    {@code true} when the listener was registered; {@code false}
     *                when the run already ended or the subscriber cap was reached
     * @param cancel  removes the listener; idempotent, a no-op when not live
     */
    public record Subscription(List<RunEvent> backlog, boolean live, Runnable cancel) {
    }

    private final int capacity;
    private final int maxReplayPositions;
    private final int maxWaitingPolls;
    /** The id of the broadcaster of the room the run's reply is broadcast to, or {@code null}. */
    private final String roomId;
    private final AtomicLong nextSequence = new AtomicLong();
    private final List<RunEvent> buffer;
    private volatile RunJournal journal = RunJournal.NOOP;
    private volatile String runId;
    /** Guarded by {@code buffer}. */
    private final List<Consumer<RunEvent>> subscribers = new ArrayList<>();
    /** Guarded by {@code buffer}: consumer -> the listener of its poll waiting for the next event. */
    private final Map<String, Consumer<RunEvent>> waitingPolls = new LinkedHashMap<>();
    /** Guarded by {@code buffer}: a terminal event was captured. */
    private boolean terminated;
    /** Guarded by {@code buffer}: the terminal event that ended the run, or {@code null} while it runs. */
    private RunEvent terminal;
    /** Completed with the terminal event once the run ended, after its listeners were handed it. */
    private final java.util.concurrent.CompletableFuture<RunEvent> ended = new java.util.concurrent.CompletableFuture<>();
    /**
     * Guarded by {@code buffer}: consumer -> sequence of the last event handed
     * to it, for the consumers a {@link #claimNew} or {@link #claim} handed
     * events to, least recently used first. Only a claim takes a place here,
     * so the frames other clients received never push a consumer out.
     */
    private final Map<String, Long> positions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > maxReplayPositions;
        }
    };
    /**
     * Guarded by {@code buffer}: consumer -> sequence of the last event whose
     * frame it received ({@link #handed}), for the consumers not in
     * {@link #positions}; at most {@link #maxReplayPositions} of them, the least
     * recently served forgotten first. A claim moves the consumer to
     * {@link #positions}; a consumer gone for good ({@link #forget}) leaves both.
     */
    private final Map<String, Long> receipts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > maxReplayPositions;
        }
    };
    /**
     * Guarded by {@code buffer}: the tracking ids a reattach of which was
     * refused for not owning the run, each warned about once; at most
     * {@link #maxReplayPositions} of them, the first ones (Correctness
     * Invariant #3). One past them is not warned about at all.
     */
    private final java.util.Set<String> refused = new java.util.HashSet<>();
    /**
     * Guarded by {@code buffer}: the id of the session that wrote the frames of
     * the run's events to its client or room ({@link #wroteFrame}), or
     * {@code null} while none was written that way.
     */
    private String frameSessionId;
    /**
     * Guarded by {@code buffer}: event sequence -> the {@code seq} of the frame
     * that session wrote for it, for the retained events only (an event the
     * capacity evicts leaves), so it holds at most {@link #capacity()} entries.
     */
    private final Map<Long, Long> frameSequences = new java.util.HashMap<>();
    /**
     * Guarded by {@code buffer}: event sequence -> the text events a retained
     * text event stands for since they were joined ({@link #compact}), for the
     * retained events only.
     */
    private final Map<Long, Joined> joined = new java.util.HashMap<>();
    /** The most characters of payload the run retains ({@link #DEFAULT_MAX_CHARS}). */
    private final int maxChars;
    /** Guarded by {@code buffer}: the characters of the payloads retained. */
    private long retainedChars;
    /**
     * Guarded by {@code buffer}: the retained event standing for the events
     * dropped to stay within the bounds ({@link #TRUNCATED_NOTICE}), or
     * {@code null} while none was.
     */
    private RunEvent dropped;

    /**
     * The text events one retained text event stands for: of each, in order,
     * its sequence, the {@code seq} of its frame ({@code -1} when none was
     * numbered), where its text ends in the joined payload and when it was
     * captured (nanoseconds since the epoch).
     */
    private record Joined(long[] sequences, long[] frames, int[] ends, long[] times) {

        int size() {
            return sequences.length;
        }

        Instant time(int i) {
            return Instant.ofEpochSecond(0, times[i]);
        }

        /** The parts from {@code from} on, their text starting at {@code cut} of the joined payload. */
        Joined from(int from, int cut) {
            var rest = java.util.Arrays.copyOfRange(ends, from, ends.length);
            for (var i = 0; i < rest.length; i++) {
                rest[i] -= cut;
            }
            return new Joined(java.util.Arrays.copyOfRange(sequences, from, sequences.length),
                    java.util.Arrays.copyOfRange(frames, from, frames.length), rest,
                    java.util.Arrays.copyOfRange(times, from, times.length));
        }
    }

    /** {@code instant} in nanoseconds since the epoch. */
    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    public RunEventReplayBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public RunEventReplayBuffer(int capacity) {
        this(null, capacity);
    }

    /**
     * A buffer of the run {@code runId}, which its warnings name.
     *
     * @param runId    the run's id; {@code null} for a buffer of no registered run,
     *                 which {@link RunRegistry} names when it registers the run
     * @param capacity the events it retains
     */
    public RunEventReplayBuffer(String runId, int capacity) {
        this(runId, capacity, MAX_REPLAY_POSITIONS, MAX_WAITING_POLLS);
    }

    /**
     * A buffer of the run {@code runId}, with its long-polling bounds.
     *
     * @param runId              the run's id; {@code null} for a buffer of no
     *                           registered run, which {@link RunRegistry} names
     *                           when it registers the run
     * @param capacity           the events it retains
     * @param maxReplayPositions the consumers whose replay position it remembers,
     *                           and, apart from them, those whose frames received
     *                           as a long-polling reply it remembers
     * @param maxWaitingPolls    the consumers whose poll may wait for its next event
     * @throws IllegalArgumentException when a bound is not positive
     */
    public RunEventReplayBuffer(String runId, int capacity, int maxReplayPositions, int maxWaitingPolls) {
        this(runId, capacity, maxReplayPositions, maxWaitingPolls, null, DEFAULT_MAX_CHARS);
    }

    /**
     * A buffer of the run {@code runId} retaining at most {@code capacity}
     * events and {@code maxChars} characters of payload.
     */
    RunEventReplayBuffer(String runId, int capacity, int maxChars) {
        this(runId, capacity, MAX_REPLAY_POSITIONS, MAX_WAITING_POLLS, null, maxChars);
    }

    private RunEventReplayBuffer(String runId, int capacity, int maxReplayPositions, int maxWaitingPolls,
                                 String roomId, int maxChars) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be > 0, got " + maxChars);
        }
        this.maxChars = maxChars;
        if (maxReplayPositions <= 0) {
            throw new IllegalArgumentException("maxReplayPositions must be > 0, got " + maxReplayPositions);
        }
        if (maxWaitingPolls <= 0) {
            throw new IllegalArgumentException("maxWaitingPolls must be > 0, got " + maxWaitingPolls);
        }
        this.capacity = capacity;
        this.maxReplayPositions = maxReplayPositions;
        this.maxWaitingPolls = maxWaitingPolls;
        this.runId = runId;
        this.roomId = roomId;
        this.buffer = Collections.synchronizedList(new ArrayList<>(capacity));
    }

    /**
     * A buffer of {@link #DEFAULT_CAPACITY} events for the run {@code runId},
     * with the long-polling bounds the init parameters
     * {@value #MAX_REPLAY_POSITIONS_PARAM} and {@value #MAX_WAITING_POLLS_PARAM}
     * of {@code config} set. A value that is not a positive integer is ignored,
     * with a warning, for the default.
     *
     * @param runId  the run's id
     * @param config the run's application; {@code null} for the defaults
     */
    public static RunEventReplayBuffer forRun(String runId, AtmosphereConfig config) {
        return new RunEventReplayBuffer(runId, DEFAULT_CAPACITY,
                positive(config, MAX_REPLAY_POSITIONS_PARAM, MAX_REPLAY_POSITIONS),
                positive(config, MAX_WAITING_POLLS_PARAM, MAX_WAITING_POLLS));
    }

    /**
     * {@link #forRun}, for a run whose reply is broadcast to a room
     * ({@code @AiEndpoint(broadcastReply = true)}): every member of the room
     * receives its frames, the run id with them, so only the run's own client,
     * the tracking id that sent the prompt, holds its reattach grace off
     * ({@code RunReattachSupport}).
     *
     * @param runId  the run's id
     * @param config the run's application; {@code null} for the defaults
     * @param roomId the id of the room's broadcaster
     */
    public static RunEventReplayBuffer forRoomRun(String runId, AtmosphereConfig config, String roomId) {
        Objects.requireNonNull(roomId, "roomId");
        return new RunEventReplayBuffer(runId, DEFAULT_CAPACITY,
                positive(config, MAX_REPLAY_POSITIONS_PARAM,
                        org.atmosphere.ai.LongPollingRoomReplies.memberLimit(config)),
                positive(config, MAX_WAITING_POLLS_PARAM, MAX_WAITING_POLLS), roomId, DEFAULT_MAX_CHARS);
    }

    /**
     * The id of the broadcaster of the room the run's reply is broadcast to
     * ({@link #forRoomRun}), or {@code null} when it reaches its own client only.
     */
    public String roomId() {
        return roomId;
    }

    /** The run this run handed its turn off to, or {@code null}. */
    private volatile String handedOffTo;

    /** Record that this run handed its turn off to the run {@code runId} (another agent's). */
    public void handedOffTo(String runId) {
        this.handedOffTo = runId;
        for (var listener : handoffListeners) {
            // Removing it first: a listener hears of the handoff once.
            if (handoffListeners.remove(listener)) {
                listener.accept(runId);
            }
        }
    }

    /** Who follows this run on to the run it hands its turn off to ({@link #onHandoff}). */
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<String>> handoffListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Hand {@code listener} the id of the run this run hands its turn off to
     * from now on, once, when it does. The agent that hands its turn off may
     * leave its own run open, so a client following that run learns of the
     * handoff here rather than from the run's end.
     *
     * @param listener takes the id of the run the turn was handed to
     * @return removes the listener
     */
    public Runnable onHandoff(Consumer<String> listener) {
        handoffListeners.add(listener);
        return () -> handoffListeners.remove(listener);
    }

    /**
     * The run this run handed its turn off to, or {@code null}: a client that
     * reattaches to this run follows the turn there.
     */
    public String handedOffTo() {
        return handedOffTo;
    }

    /** Whether the run's id is one its client minted for its prompt ({@link ClientRunIds}). */
    private volatile boolean clientRunId;

    /** Mark the run's id as one its client minted for its prompt. */
    public void clientMintedId() {
        this.clientRunId = true;
    }

    /**
     * Whether the run's id is one its client minted for its prompt: such a
     * client is told, as it reattaches, that the server has the run
     * ({@link RunReattachSupport#acknowledgeRun}); one the server named is not
     * written anything more than before.
     */
    public boolean isClientMintedId() {
        return clientRunId;
    }

    private static int positive(AtmosphereConfig config, String name, int defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        String raw;
        try {
            raw = config.getInitParameter(name);
        } catch (RuntimeException e) {
            logger.trace("Unable to read {}", name, e);
            return defaultValue;
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            var value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            logger.trace("{} is not a number", name, e);
        }
        logger.warn("Ignoring {}={}: not a positive integer; using {}", name, raw, defaultValue);
        return defaultValue;
    }

    /** The id of the run this buffer holds the events of, or {@code null} when it was given none. */
    public String runId() {
        return runId;
    }

    /** Names the run of a buffer created without an id; a no-op once it has one. */
    void nameRun(String id) {
        if (id == null) {
            return;
        }
        synchronized (buffer) {
            if (runId == null) {
                runId = id;
            }
        }
    }

    /** The run as the warnings name it. */
    private String describe() {
        var id = runId;
        return id != null ? "Run " + id : "A run with no id";
    }

    /**
     * Attach a durable journal so every subsequent {@link #capture} is also
     * persisted under {@code runId}. Idempotent and one-way: only the first
     * non-NOOP attach takes effect, so a registry cannot accidentally
     * rebind an active buffer to a different journal mid-run (Ownership —
     * Correctness Invariant #1). A {@code null} or NOOP journal leaves the
     * buffer in its default (in-memory only) state.
     */
    public void attachJournal(RunJournal journal, String runId) {
        if (journal == null || journal == RunJournal.NOOP || runId == null) {
            return;
        }
        synchronized (buffer) {
            if (this.journal == RunJournal.NOOP) {
                this.journal = journal;
                this.runId = runId;
            }
        }
    }

    /**
     * Capture an event. Returns the stored {@link RunEvent} with its
     * assigned sequence so the caller can thread it through the live
     * stream.
     */
    public RunEvent capture(String type, String payload) {
        return append(type, payload, false, null);
    }

    /**
     * {@link #capture} an event unless the run already ended: nothing is
     * recorded after the terminal event, so the replay ends with the end the
     * run's clients were sent.
     *
     * @return the stored event, or {@code null} when the run had already ended
     */
    RunEvent captureIfOpen(String type, String payload) {
        return append(type, payload, true, null);
    }

    /**
     * {@link #captureIfOpen}, the event numbered by {@code numbering}, the
     * session whose frame stands for it, before anything listening to the run
     * sees it ({@link #wroteFrame}).
     */
    RunEvent captureIfOpen(String type, String payload, Numbering numbering) {
        return append(type, payload, true, numbering);
    }

    /**
     * How the session that writes a run's frames numbers the next one: its id,
     * and its frame counter, read once per event as it is captured.
     *
     * @param sessionId the id of the session
     * @param next      the {@code seq} of the session's next frame
     */
    public record Numbering(String sessionId, java.util.function.LongSupplier next) {
    }

    /**
     * End the run with a terminal {@code complete}, {@code error} or
     * {@code cancelled} event
     * unless one was already captured: the event is stored, delivered to every
     * live listener and the listeners are released, exactly as a captured
     * terminal event is. Idempotent; the first terminal event wins.
     *
     * @return {@code true} when this call ended the run, {@code false} when it
     *         had already ended
     */
    public boolean terminate(String type, String payload) {
        if (!isTerminal(type)) {
            throw new IllegalArgumentException("not a terminal event type: " + type);
        }
        return terminateEvent(type, payload) != null;
    }

    /**
     * {@link #terminate}, returning the terminal event stored, or {@code null}
     * when the run had already ended.
     */
    RunEvent terminateEvent(String type, String payload) {
        return terminateEvent(type, payload, null);
    }

    /** {@link #terminateEvent}, the event numbered by {@code numbering} ({@link #captureIfOpen(String, String, Numbering)}). */
    RunEvent terminateEvent(String type, String payload, Numbering numbering) {
        if (!isTerminal(type)) {
            throw new IllegalArgumentException("not a terminal event type: " + type);
        }
        return append(type, payload, true, numbering);
    }

    /** The sequence of the last event captured, or {@code -1} when none was. */
    long lastSequence() {
        return nextSequence.get() - 1;
    }

    /**
     * The session {@code sessionId} numbered the frame of the retained event
     * {@code eventSequence} {@code frameSequence}: the frame it wrote to the
     * run's client, or to every member of a {@code broadcastReply} run's room
     * (an event its capture numbered already keeps that number, which the
     * frame carries, as does one its session, its client gone, never wrote). A client received the run's frames under that session's id
     * and numbering, so a replay writes them under the same ({@link #frameOf}):
     * a frame the client already had is then the same {@code (sessionId, seq)}
     * it holds, which it drops, and one it missed continues the numbering of
     * those it has. The first number given an event is kept; a session other
     * than the first one that numbered, or an event no longer retained, is
     * ignored.
     *
     * @param eventSequence the sequence of the event the frame stands for
     * @param sessionId     the id of the session that numbered it
     * @param frameSequence the {@code seq} of the frame
     */
    public void wroteFrame(long eventSequence, String sessionId, long frameSequence) {
        if (sessionId == null) {
            return;
        }
        synchronized (buffer) {
            if (frameSessionId == null) {
                frameSessionId = sessionId;
            } else if (!frameSessionId.equals(sessionId)) {
                return;
            }
            // A retained event only: one joined into another, or dropped, is not.
            var at = indexAtOrAfter(eventSequence);
            if (at < buffer.size() && buffer.get(at).sequence() == eventSequence) {
                frameSequences.putIfAbsent(eventSequence, frameSequence);
            }
        }
    }

    /**
     * The {@code seq} session {@code sessionId} gave the frame of the retained
     * event {@code eventSequence} as it was captured, or {@code -1} when it
     * gave none.
     */
    public long numberedBy(long eventSequence, String sessionId) {
        synchronized (buffer) {
            if (sessionId == null || !sessionId.equals(frameSessionId)) {
                return -1;
            }
            // Also an event joined into another since ({@link #compact}).
            return frameOf(new RunEvent(eventSequence, TEXT_TYPE, null, Instant.EPOCH));
        }
    }

    /**
     * The id of the session that numbered the run's frames ({@link #wroteFrame}),
     * or {@code null} when none was numbered that way.
     */
    public String frameSessionId() {
        synchronized (buffer) {
            return frameSessionId;
        }
    }

    /**
     * The {@code seq} that session gave the frame of {@code event}, or
     * {@code -1} when it gave none ({@link #wroteFrame}).
     */
    public long frameOf(RunEvent event) {
        synchronized (buffer) {
            var seq = frameSequences.get(event.sequence());
            if (seq != null) {
                return seq;
            }
            // A text event joined into a retained one ({@link #compact}): its own frame.
            var at = indexAtOrAfter(event.sequence());
            var parts = at < buffer.size() ? joined.get(buffer.get(at).sequence()) : null;
            if (parts != null) {
                var i = java.util.Arrays.binarySearch(parts.sequences(), event.sequence());
                if (i >= 0) {
                    return parts.frames()[i];
                }
            }
            return -1;
        }
    }

    /**
     * {@code events}, retained events in order, with each text event joined
     * into another ({@link #compact}) back in its place: its own sequence,
     * text, frame ({@link #frameOf}) and capture time, so a replay writes the
     * frames the run's clients received, each filtered by what its client
     * holds as before the join. Caller holds the lock.
     */
    private List<RunEvent> expand(List<RunEvent> events) {
        if (joined.isEmpty()) {
            return List.copyOf(events);
        }
        var out = new ArrayList<RunEvent>(events.size() + 64);
        for (var event : events) {
            var parts = joined.get(event.sequence());
            if (parts == null) {
                out.add(event);
                continue;
            }
            var start = 0;
            for (var i = 0; i < parts.size(); i++) {
                out.add(new RunEvent(parts.sequences()[i], event.type(),
                        event.payload().substring(start, parts.ends()[i]), parts.time(i)));
                start = parts.ends()[i];
            }
        }
        return List.copyOf(out);
    }

    /**
     * The sequence of the oldest retained event, or of the first text event
     * joined into it ({@link #compact}). Caller holds the lock; the buffer is
     * not empty.
     */
    private long firstSequence() {
        var parts = joined.get(buffer.get(0).sequence());
        return parts != null ? parts.sequences()[0] : buffer.get(0).sequence();
    }

    /**
     * The index of the first retained event whose sequence is at least
     * {@code sequence}, or the number of retained events when there is none.
     * Caller holds the lock; the retained events are in ascending sequence.
     */
    private int indexAtOrAfter(long sequence) {
        int low = 0;
        int high = buffer.size();
        while (low < high) {
            var mid = (low + high) >>> 1;
            if (buffer.get(mid).sequence() < sequence) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** Whether a terminal {@code complete} / {@code error} / {@code cancelled} event was captured. */
    public boolean isTerminated() {
        synchronized (buffer) {
            return terminated;
        }
    }

    /**
     * The terminal {@code complete} / {@code error} / {@code cancelled} event
     * that ended the run, kept whatever the capacity evicted; empty while the
     * run goes on.
     */
    public java.util.Optional<RunEvent> terminal() {
        synchronized (buffer) {
            return java.util.Optional.ofNullable(terminal);
        }
    }

    /**
     * Completes with the terminal event that ended the run, once its live
     * listeners were handed it; already complete when the run ended. Takes
     * none of the {@link #MAX_SUBSCRIBERS} places, and a run that ends
     * releases what was chained on it.
     *
     * @return a stage completed with the run's terminal event
     */
    public java.util.concurrent.CompletionStage<RunEvent> whenTerminated() {
        return ended.minimalCompletionStage();
    }

    private RunEvent append(String type, String payload, boolean onlyIfOpen, Numbering numbering) {
        RunEvent event;
        List<Consumer<RunEvent>> listeners;
        synchronized (buffer) {
            if (onlyIfOpen && terminated) {
                return null;
            }
            event = new RunEvent(
                    nextSequence.getAndIncrement(),
                    type,
                    payload,
                    Instant.now());
            buffer.add(event);
            retainedChars += cost(event);
            // Numbered before a listener sees it: a frame forwarded or handed to
            // a poll now is written in the session's numbering, as its replay is.
            if (numbering != null && numbering.sessionId() != null
                    && (frameSessionId == null || frameSessionId.equals(numbering.sessionId()))) {
                frameSessionId = numbering.sessionId();
                frameSequences.put(event.sequence(), numbering.next().getAsLong());
            }
            bound();
            listeners = listeners();
            if (isTerminal(type)) {
                terminated = true;
                if (terminal == null) {
                    terminal = event;
                }
                subscribers.clear();
                waitingPolls.clear();
            }
        }
        mirrorToJournal(event);
        // Outside the lock: a listener writes to a network connection, and a
        // reattach holds its own write lock while it subscribes.
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.warn("A run event subscriber failed on event {} ({}); dropping it ({})",
                        event.sequence(), describe(), e.toString());
                unsubscribe(listener);
            }
        }
        if (isTerminal(type)) {
            // The first terminal event is the run's end; a later one changes nothing.
            ended.complete(terminal().orElse(event));
        }
        return event;
    }

    /** The characters of {@code event}'s payload. */
    private static int length(RunEvent event) {
        return event.payload() == null ? 0 : event.payload().length();
    }

    /**
     * What one retained event, or one text event joined into another, costs
     * on top of its payload, in characters: its bookkeeping (a joined part's
     * sequence, frame, end and capture time, 28 bytes).
     */
    private static final int PART_COST = 14;

    /** What {@code event}, a retained event, counts against the character bound. Caller holds the lock. */
    private long cost(RunEvent event) {
        var parts = joined.get(event.sequence());
        return length(event) + (long) PART_COST * (parts != null ? parts.size() : 1);
    }

    /**
     * Keep the run within its bounds (Correctness Invariant #3): past
     * {@link #capacity()} events, join the consecutive text events
     * ({@link #compact}); then, while still past it, or past
     * {@code maxChars} characters, drop the oldest events but the run's
     * prompt ({@link #dropOldest}), down to seven eighths of the characters
     * once over them, so a run that keeps writing past the bound is not cut
     * (and its joined text copied) on every event. Caller holds the lock.
     */
    private void bound() {
        if (buffer.size() <= capacity && retainedChars <= maxChars) {
            return;
        }
        if (buffer.size() > capacity) {
            compact();
        }
        var target = retainedChars > maxChars ? maxChars - maxChars / 8 : maxChars;
        while ((buffer.size() > capacity || retainedChars > target) && dropOldest(target)) {
            // dropOldest dropped one event, or the oldest text of one.
        }
    }

    /**
     * Join every run of consecutive text events (frames of the reply's text)
     * into one event: its payload their texts in order, its sequence, frame
     * and timestamp those of the last of them, with where each one's text
     * ends ({@link Joined}), which every read hands back one by one
     * ({@link #expand}). Events of
     * another type keep their place, and so do the run's prompt and the
     * notice of dropped events. Text events are joined only with text events
     * numbered the same way (all with a frame, or none). Caller holds the lock.
     */
    private void compact() {
        var out = new ArrayList<RunEvent>(buffer.size());
        var i = 0;
        while (i < buffer.size()) {
            var event = buffer.get(i);
            var j = i + 1;
            if (joinable(event)) {
                var numbered = frameSequences.containsKey(event.sequence());
                while (j < buffer.size() && joinable(buffer.get(j))
                        && frameSequences.containsKey(buffer.get(j).sequence()) == numbered) {
                    j++;
                }
            }
            out.add(j - i > 1 ? join(buffer.subList(i, j)) : event);
            i = j;
        }
        if (out.size() < buffer.size()) {
            buffer.clear();
            buffer.addAll(out);
        }
    }

    /** Whether {@code event} may be joined with its neighbours: a text event, not the notice of dropped ones. */
    private boolean joinable(RunEvent event) {
        return TEXT_TYPE.equals(event.type()) && event != dropped;
    }

    /** One event standing for {@code events}, consecutive text events ({@link #compact}). Caller holds the lock. */
    private RunEvent join(List<RunEvent> events) {
        var count = 0;
        for (var event : events) {
            var parts = joined.get(event.sequence());
            count += parts != null ? parts.size() : 1;
        }
        var sequences = new long[count];
        var frames = new long[count];
        var ends = new int[count];
        var times = new long[count];
        var text = new StringBuilder();
        var k = 0;
        for (var event : events) {
            var parts = joined.remove(event.sequence());
            var frame = frameSequences.remove(event.sequence());
            if (parts != null) {
                for (var p = 0; p < parts.size(); p++) {
                    sequences[k] = parts.sequences()[p];
                    frames[k] = parts.frames()[p];
                    ends[k] = text.length() + parts.ends()[p];
                    times[k] = parts.times()[p];
                    k++;
                }
            } else {
                sequences[k] = event.sequence();
                frames[k] = frame == null ? -1 : frame;
                ends[k] = text.length() + length(event);
                times[k] = nanos(event.timestamp());
                k++;
            }
            if (event.payload() != null) {
                text.append(event.payload());
            }
        }
        var last = events.get(events.size() - 1);
        if (frames[count - 1] >= 0) {
            frameSequences.put(last.sequence(), frames[count - 1]);
        }
        joined.put(last.sequence(), new Joined(sequences, frames, ends, times));
        return new RunEvent(last.sequence(), TEXT_TYPE, text.toString(), last.timestamp());
    }

    /**
     * Drop the oldest retained event but the run's prompt and the notice of
     * dropped events, or, when only characters are over the bound, the
     * oldest text of a joined text event, just enough of it. A run whose
     * frames are numbered keeps, in their place, the notice of the events it
     * dropped ({@link #TRUNCATED_NOTICE}), under the sequence and frame of
     * the last of them. Caller holds the lock.
     *
     * @param target the characters to bring the run down to
     * @return {@code false} when nothing is left to drop
     */
    private boolean dropOldest(long target) {
        var at = -1;
        for (var i = 0; i < buffer.size(); i++) {
            var event = buffer.get(i);
            if (event != dropped && !org.atmosphere.ai.filter.AiStreamMessage.PROMPT_TYPE.equals(event.type())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return false;
        }
        var event = buffer.get(at);
        var parts = joined.get(event.sequence());
        long lastSequence;
        long lastFrame;
        Instant when;
        if (buffer.size() <= capacity && parts != null && parts.size() > 1) {
            // Over the characters only: drop the oldest parts of the text, as
            // few as bring the run within the bound, keeping its newest part.
            var excess = retainedChars - target;
            var count = 1;
            while (count < parts.size() - 1 && parts.ends()[count - 1] + (long) PART_COST * count < excess) {
                count++;
            }
            var cut = parts.ends()[count - 1];
            lastSequence = parts.sequences()[count - 1];
            lastFrame = parts.frames()[count - 1];
            when = parts.time(count - 1);
            var rest = parts.from(count, cut);
            if (rest.size() > 1) {
                joined.put(event.sequence(), rest);
            } else {
                joined.remove(event.sequence());
            }
            buffer.set(at, new RunEvent(event.sequence(), event.type(), event.payload().substring(cut),
                    event.timestamp()));
            retainedChars -= cut + (long) PART_COST * count;
        } else {
            retainedChars -= cost(event);
            buffer.remove(at);
            joined.remove(event.sequence());
            var frame = frameSequences.remove(event.sequence());
            lastSequence = event.sequence();
            lastFrame = frame == null ? -1 : frame;
            when = event.timestamp();
        }
        noteDropped(at, lastSequence, lastFrame, when);
        return true;
    }

    /**
     * Put the notice of the dropped events at {@code at}, where the last one
     * dropped was, in place of the notice of those dropped before, for a run
     * whose frames are numbered: a replay writes it under the frame of the
     * last event dropped. Caller holds the lock.
     */
    private void noteDropped(int at, long lastSequence, long lastFrame, Instant when) {
        var first = dropped == null;
        var previousFrame = -1L;
        if (dropped != null) {
            var kept = frameSequences.get(dropped.sequence());
            previousFrame = kept == null ? -1 : kept;
            var index = buffer.indexOf(dropped);
            if (index >= 0) {
                buffer.remove(index);
                if (index < at) {
                    at--;
                }
            }
            frameSequences.remove(dropped.sequence());
            retainedChars -= cost(dropped);
            dropped = null;
        }
        // An event dropped with no frame of its own keeps the frame of the
        // dropped events before it: the notice stands for them all.
        var frame = lastFrame >= 0 ? lastFrame : previousFrame;
        if (frameSessionId == null || frame < 0) {
            return;
        }
        dropped = new RunEvent(lastSequence, TEXT_TYPE, TRUNCATED_NOTICE, when);
        buffer.add(at, dropped);
        frameSequences.put(lastSequence, frame);
        retainedChars += cost(dropped);
        if (first) {
            logger.warn("{} outgrew its replay bounds ({} events, {} characters): a client that reattaches "
                    + "is replayed the rest of it with a notice of what was dropped", describe(), capacity,
                    maxChars);
        }
    }

    /**
     * Return the retained backlog and, unless the run already ended or
     * {@link #MAX_SUBSCRIBERS} listeners are registered, deliver every later
     * capture to {@code listener}. Backlog and registration happen atomically,
     * so the two together are the complete, duplicate-free event stream.
     */
    public Subscription subscribe(Consumer<RunEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (buffer) {
            var backlog = expand(buffer);
            if (terminated) {
                return new Subscription(backlog, false, () -> { });
            }
            if (subscribers.size() >= MAX_SUBSCRIBERS) {
                logger.warn("{} already has {} live subscribers; this WebSocket, SSE or streaming "
                        + "reattach gets the backlog only", describe(), MAX_SUBSCRIBERS);
                return new Subscription(backlog, false, () -> { });
            }
            subscribers.add(listener);
            return new Subscription(backlog, true, () -> unsubscribe(listener));
        }
    }

    /**
     * Unless the run already ended, deliver every later capture to
     * {@code listener}, the long-polling poll of {@code consumer} waiting for the
     * run's next event, in place of an older poll of {@code consumer} still
     * waiting. Not counted against {@link #MAX_SUBSCRIBERS}; refused when
     * {@link #maxWaitingPolls()} other consumers' polls wait. Backlog and
     * registration happen atomically, as for {@link #subscribe}.
     */
    public Subscription awaitNext(String consumer, Consumer<RunEvent> listener) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(listener, "listener");
        synchronized (buffer) {
            var backlog = expand(buffer);
            if (terminated) {
                return new Subscription(backlog, false, () -> { });
            }
            if (waitingPolls.size() >= maxWaitingPolls && !waitingPolls.containsKey(consumer)) {
                logger.warn("{} already has polls of {} clients waiting for its next event; this poll "
                        + "waits for a reply or its suspend timeout, and its client's next poll gets what "
                        + "it missed", describe(), maxWaitingPolls);
                return new Subscription(backlog, false, () -> { });
            }
            waitingPolls.put(consumer, listener);
            return new Subscription(backlog, true, () -> {
                synchronized (buffer) {
                    waitingPolls.remove(consumer, listener);
                }
            });
        }
    }

    /** Number of live listeners: subscribers and waiting polls. */
    public int subscriberCount() {
        synchronized (buffer) {
            return subscribers.size() + waitingPolls.size();
        }
    }

    /** Guarded by {@code buffer}: a copy of every live listener. */
    private List<Consumer<RunEvent>> listeners() {
        if (subscribers.isEmpty() && waitingPolls.isEmpty()) {
            return List.of();
        }
        var all = new ArrayList<Consumer<RunEvent>>(subscribers.size() + waitingPolls.size());
        all.addAll(subscribers);
        all.addAll(waitingPolls.values());
        return List.copyOf(all);
    }

    private void unsubscribe(Consumer<RunEvent> listener) {
        synchronized (buffer) {
            subscribers.remove(listener);
            waitingPolls.values().remove(listener);
        }
    }

    /**
     * Whether {@code type} ends a run: {@code complete}, {@code error} or
     * {@code cancelled}.
     */
    public static boolean isTerminal(String type) {
        return "complete".equals(type) || "error".equals(type)
                || org.atmosphere.ai.RunCancelledException.FRAME_TYPE.equals(type);
    }

    /**
     * Re-seed this buffer from previously journaled events, preserving their
     * original sequence numbers so replay ordering survives a restart. Used
     * by {@link RunRegistry#rehydrate()}. Advances the internal sequence
     * counter past the highest restored sequence so any further captures do
     * not collide with rehydrated history. A restored terminal event ends the
     * run, as a captured one does.
     */
    void restore(List<RunEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        synchronized (buffer) {
            var maxSeq = -1L;
            for (var event : events) {
                buffer.add(event);
                retainedChars += cost(event);
                maxSeq = Math.max(maxSeq, event.sequence());
                if (isTerminal(event.type())) {
                    terminated = true;
                    if (terminal == null) {
                        terminal = event;
                    }
                }
            }
            // Joined and dropped as a run capturing them would have.
            bound();
            nextSequence.set(maxSeq + 1);
        }
        terminal().ifPresent(ended::complete);
    }

    private void mirrorToJournal(RunEvent event) {
        var j = journal;
        if (j == RunJournal.NOOP) {
            return;
        }
        try {
            j.appendEvent(runId, event);
        } catch (RuntimeException e) {
            // Best-effort persistence (Correctness Invariant #3): a journal
            // failure must never break the live stream. The run falls back
            // to in-memory-only replay — the FIRST failure is an
            // operator-visible incident (durability silently off is exactly
            // what a provisioned deployment must not discover at restore
            // time); repeats stay at TRACE off the hot path.
            if (DEGRADED_WARNED.compareAndSet(false, true)) {
                logger.warn("RunJournal.appendEvent failed — durable run replay has "
                        + "degraded to IN-MEMORY for run {}: buffered events will not "
                        + "survive a restart until the journal recovers ({}: {})",
                        runId, e.getClass().getSimpleName(), e.getMessage(), e);
            } else {
                logger.trace("RunJournal.appendEvent failed for run {} ({}): {}",
                        runId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /** One-shot latch: the first journal failure warns, the rest trace. */
    private static final java.util.concurrent.atomic.AtomicBoolean DEGRADED_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test isolation for the one-shot degradation WARN. */
    static void resetDegradationWarningForTests() {
        DEGRADED_WARNED.set(false);
    }

    /**
     * Snapshot of all currently retained events, oldest first, each text
     * event joined into another ({@link #compact}) in its own place.
     */
    public List<RunEvent> snapshot() {
        synchronized (buffer) {
            return expand(buffer);
        }
    }

    /**
     * Events captured with {@code sequence >= fromSequence}, oldest first.
     * Used when a reconnecting client already knows the last sequence it
     * saw and only needs what came after.
     */
    public List<RunEvent> replayFrom(long fromSequence) {
        synchronized (buffer) {
            var out = new ArrayList<RunEvent>();
            for (var event : expand(buffer)) {
                if (event.sequence() >= fromSequence) {
                    out.add(event);
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * The retained events not handed to {@code consumer} yet, oldest first; they
     * count as handed to it from now on. Two calls for one consumer never
     * return the same event.
     */
    public List<RunEvent> claimNew(String consumer) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (buffer) {
            var last = position(consumer);
            var out = new ArrayList<RunEvent>();
            for (var event : expand(buffer)) {
                if (event.sequence() > last) {
                    out.add(event);
                }
            }
            if (!out.isEmpty()) {
                claimed(consumer, out.get(out.size() - 1).sequence());
            }
            return List.copyOf(out);
        }
    }

    /**
     * Whether {@code event} was not handed to {@code consumer} yet; it counts as
     * handed to it from now on.
     */
    public boolean claim(String consumer, RunEvent event) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(event, "event");
        synchronized (buffer) {
            if (event.sequence() <= position(consumer)) {
                return false;
            }
            claimed(consumer, event.sequence());
            return true;
        }
    }

    /**
     * {@code consumer} received the frames of the run's events {@code first} to
     * {@code last}, every one of them, some other way than a replay (a
     * long-polling reply). When they follow on from what it was handed before
     * without a gap, no {@link #claimNew} or {@link #claim} hands it those
     * events. Otherwise nothing changes: the events between what it was handed
     * and {@code first} never reached it, so a replay hands them to it, and
     * these events after them again. Events no longer retained leave no gap:
     * no replay can hand them. A position already past {@code last} stays.
     * Callers record the spans of one consumer in the order of their
     * {@code first}, so that one may follow on from another. For a consumer
     * no claim handed events to, this is remembered for at most
     * {@link #maxReplayPositions()} such consumers, the least recently served
     * forgotten first, so it never makes the run forget a consumer a claim
     * handed events to.
     */
    public void handed(String consumer, long first, long last) {
        Objects.requireNonNull(consumer, "consumer");
        if (last < first) {
            return;
        }
        synchronized (buffer) {
            var current = position(consumer);
            var from = buffer.isEmpty() ? current : Math.max(current, firstSequence() - 1);
            if (first > from + 1 || last <= current) {
                return;
            }
            if (positions.containsKey(consumer)) {
                positions.put(consumer, last);
            } else {
                receipts.put(consumer, last);
            }
        }
    }

    /**
     * Guarded by {@code buffer}: the sequence of the last event handed to
     * {@code consumer}, or {@code -1} when it is not remembered.
     */
    private long position(String consumer) {
        var claimed = positions.get(consumer);
        if (claimed != null) {
            return claimed;
        }
        return receipts.getOrDefault(consumer, -1L);
    }

    /** Guarded by {@code buffer}: events up to {@code sequence} were claimed by {@code consumer}. */
    private void claimed(String consumer, long sequence) {
        receipts.remove(consumer);
        positions.put(consumer, sequence);
    }

    /**
     * {@code consumer} is gone for good (it left its room, or no poll of it was
     * seen for its time to live): the run forgets its replay position, what it
     * received and its waiting poll, so the bounds count the clients present,
     * not every client that ever polled. A consumer that comes back after all is
     * handed the retained events again.
     */
    public void forget(String consumer) {
        if (consumer == null) {
            return;
        }
        synchronized (buffer) {
            positions.remove(consumer);
            receipts.remove(consumer);
            waitingPolls.remove(consumer);
        }
    }

    /** The consumers whose replay position or received frames this run remembers; for tests. */
    int rememberedConsumers() {
        synchronized (buffer) {
            return positions.size() + receipts.size();
        }
    }

    /**
     * Give back to {@code consumer} the events it claimed from {@code sequence}
     * on, because they never reached it: the next {@link #claimNew} hands them
     * over again.
     */
    public void unclaim(String consumer, long sequence) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (buffer) {
            if (position(consumer) >= sequence) {
                claimed(consumer, sequence - 1);
            }
        }
    }

    /**
     * Whether the refusal of a reattach of {@code trackingId} to this run, for
     * not owning it, is the first one to warn about: {@code true} once per
     * tracking id, for at most {@link #maxReplayPositions()} tracking ids, then
     * {@code false}. In an authenticated {@code broadcastReply} room every
     * member's poll carries the run id of another member's run, and each is
     * refused; one warning per client says so without flooding the log.
     */
    public boolean firstRefusal(String trackingId) {
        var key = Objects.requireNonNullElse(trackingId, "");
        synchronized (buffer) {
            if (refused.contains(key) || refused.size() >= maxReplayPositions) {
                return false;
            }
            refused.add(key);
            return true;
        }
    }

    public int capacity() {
        return capacity;
    }

    /**
     * The consumers a claim handed events to whose replay position this run
     * remembers at most, and, apart from them, the consumers whose frames
     * received as a long-polling reply it remembers at most.
     */
    public int maxReplayPositions() {
        return maxReplayPositions;
    }

    /** The consumers whose long-polling poll may wait for this run's next event at once. */
    public int maxWaitingPolls() {
        return maxWaitingPolls;
    }

    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }
}
