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
 * Capacity is bounded at construction; when the limit is exceeded the
 * oldest entries are evicted (Correctness Invariant #3 — every buffer
 * declares a size bound). Replay consumers MUST treat missing earlier
 * sequence numbers as "replay buffer too small; history unavailable" and
 * fall back to their own recovery story rather than assuming complete
 * replay.
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

    /** Default capacity — enough for a few minutes of streaming. */
    public static final int DEFAULT_CAPACITY = 1_024;

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
        this(runId, capacity, maxReplayPositions, maxWaitingPolls, null);
    }

    private RunEventReplayBuffer(String runId, int capacity, int maxReplayPositions, int maxWaitingPolls,
                                 String roomId) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
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
                positive(config, MAX_WAITING_POLLS_PARAM, MAX_WAITING_POLLS), roomId);
    }

    /**
     * The id of the broadcaster of the room the run's reply is broadcast to
     * ({@link #forRoomRun}), or {@code null} when it reaches its own client only.
     */
    public String roomId() {
        return roomId;
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
        return append(type, payload, false);
    }

    /**
     * {@link #capture} an event unless the run already ended: nothing is
     * recorded after the terminal event, so the replay ends with the end the
     * run's clients were sent.
     *
     * @return the stored event, or {@code null} when the run had already ended
     */
    RunEvent captureIfOpen(String type, String payload) {
        return append(type, payload, true);
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
        if (!isTerminal(type)) {
            throw new IllegalArgumentException("not a terminal event type: " + type);
        }
        return append(type, payload, true);
    }

    /** The sequence of the last event captured, or {@code -1} when none was. */
    long lastSequence() {
        return nextSequence.get() - 1;
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

    private RunEvent append(String type, String payload, boolean onlyIfOpen) {
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
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
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

    /**
     * Return the retained backlog and, unless the run already ended or
     * {@link #MAX_SUBSCRIBERS} listeners are registered, deliver every later
     * capture to {@code listener}. Backlog and registration happen atomically,
     * so the two together are the complete, duplicate-free event stream.
     */
    public Subscription subscribe(Consumer<RunEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (buffer) {
            var backlog = List.copyOf(buffer);
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
            var backlog = List.copyOf(buffer);
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
                maxSeq = Math.max(maxSeq, event.sequence());
                if (isTerminal(event.type())) {
                    terminated = true;
                    if (terminal == null) {
                        terminal = event;
                    }
                }
            }
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
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

    /** Snapshot of all currently retained events, oldest first. */
    public List<RunEvent> snapshot() {
        synchronized (buffer) {
            return List.copyOf(buffer);
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
            for (var event : buffer) {
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
            for (var event : buffer) {
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
            var from = buffer.isEmpty() ? current : Math.max(current, buffer.get(0).sequence() - 1);
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
