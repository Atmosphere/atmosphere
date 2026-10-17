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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic TTL sweeper for the process-global streaming-session registries
 * ({@link DefaultStreamingSession}'s session/resource maps and
 * {@link AiStreamingSession}'s active-session map). Those maps are fed once
 * per prompt and are normally reclaimed by terminal events (complete/error)
 * or the disconnect path — but a runtime that never terminates, or a
 * container that delivers a recycled disconnect event, would otherwise leak
 * entries forever (Correctness Invariant #3: every cache must declare an
 * eviction policy). This sweeper is the same leak guard
 * {@link org.atmosphere.ai.resume.RunRegistry#sweepExpired} provides for
 * resumable runs, applied to the wire-session registries.
 *
 * <p>A session's TTL clock is refreshed on every outbound frame (send,
 * metadata, progress, event emit) and on approval routing, so a genuinely
 * live session is never reaped — only sessions idle longer than
 * {@link #ttlMs()} (default 30 minutes, override via the
 * {@value #TTL_PROPERTY} system property) are removed, mirroring the
 * MCP session TTL and {@code RunRegistry} defaults. A turn whose
 * {@code @Prompt} method returned with its session open is also ended once
 * it is open longer than that TTL after the return, however often it writes
 * ({@link RunCancelledException.Reason#TIMEOUT}).</p>
 *
 * <p>Lifecycle (Correctness Invariant #1): the sweeper thread is a daemon,
 * started lazily on first session registration via {@link #ensureStarted()}.
 * It is process-wide, so no one endpoint owns it: each {@code AiEndpointHandler}
 * that registered a session holds it ({@link #acquire()}) and lets go of it
 * when the framework destroys the endpoint ({@link #release()}), and the
 * sweeper stops when the last holder lets go — the destroy of one endpoint,
 * or of another application's in the same JVM, leaves it running for the
 * endpoints still live. {@link #shutdown()} stops it whoever holds it. The
 * sweeper restarts on the next registration, so an undeploy/redeploy cycle
 * keeps sweeping.</p>
 */
public final class StreamingSessionSweeper {

    private static final Logger logger = LoggerFactory.getLogger(StreamingSessionSweeper.class);

    /** System property overriding the idle TTL, in milliseconds. */
    public static final String TTL_PROPERTY = "atmosphere.ai.session-ttl-ms";

    /** Default idle TTL — matches {@code RunRegistry.DEFAULT_TTL} and the MCP session TTL. */
    public static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    /** Sweep cadence — frequent enough that a leak is bounded, cheap enough to be noise. */
    static final long SWEEP_INTERVAL_MS = 60_000L;

    private static final AtomicReference<ScheduledExecutorService> SWEEPER = new AtomicReference<>();

    /** Guards {@link #holders}. */
    private static final Object HOLDERS_LOCK = new Object();

    /** How many {@link #acquire()} calls no {@link #release()} answered yet. Guarded by {@link #HOLDERS_LOCK}. */
    private static int holders;

    private StreamingSessionSweeper() {
    }

    /**
     * Start the sweeper if it is not already running. Called from session
     * registration paths; cheap when already started (a volatile read).
     */
    static void ensureStarted() {
        if (SWEEPER.get() != null) {
            return;
        }
        var candidate = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().daemon().name("ai-session-sweeper").unstarted(r));
        if (SWEEPER.compareAndSet(null, candidate)) {
            candidate.scheduleAtFixedRate(StreamingSessionSweeper::sweep,
                    SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
            logger.debug("AI streaming-session TTL sweeper started (ttl={} ms, interval={} ms)",
                    ttlMs(), SWEEP_INTERVAL_MS);
        } else {
            // Lost the race — another thread installed the sweeper first.
            candidate.shutdownNow();
        }
    }

    /**
     * Hold the sweeper: it is not stopped by {@link #release()} until this
     * caller released it too. Each call is answered by one {@code release()}.
     * It does not start the sweeper: the first session registration does.
     */
    public static void acquire() {
        synchronized (HOLDERS_LOCK) {
            holders++;
        }
    }

    /**
     * Let go of a hold {@link #acquire()} took: the last holder letting go
     * stops the sweeper. A call with no hold left changes nothing.
     */
    public static void release() {
        synchronized (HOLDERS_LOCK) {
            if (holders == 0 || --holders > 0) {
                return;
            }
            shutdown();
        }
    }

    /**
     * Stop the sweeper, whoever holds it. Idempotent; whichever streaming
     * session registers after this restarts it. An endpoint's teardown lets
     * go of its hold with {@link #release()} instead, so it never stops the
     * sweeper under another endpoint still live.
     */
    public static void shutdown() {
        var executor = SWEEPER.getAndSet(null);
        if (executor != null) {
            executor.shutdownNow();
            logger.debug("AI streaming-session TTL sweeper stopped");
        }
    }

    /** How many holds are taken. Visible for testing. */
    static int holders() {
        synchronized (HOLDERS_LOCK) {
            return holders;
        }
    }

    /** Whether the sweeper thread is currently scheduled. Visible for testing. */
    static boolean isRunning() {
        return SWEEPER.get() != null;
    }

    /** One sweep pass over both registries and the parked long-polling replies. Visible for testing. */
    static void sweep() {
        try {
            var ttl = ttlMs();
            // The AI sessions first: a reaped turn the idle sweep ends with
            // its cancelled frame still reaches its client, and the room of a
            // room run, through the streaming session it closes.
            var reaped = AiStreamingSession.sweepExpired(ttl)
                    + DefaultStreamingSession.sweepExpired(ttl);
            LongPollingReplies.sweepExpired();
            if (reaped > 0) {
                logger.info("Reaped {} expired AI streaming session(s) idle longer than {} ms", reaped, ttl);
            }
        } catch (RuntimeException e) {
            // A failing pass must not kill the scheduled task; the next pass retries.
            logger.warn("AI streaming-session sweep pass failed: {}", e.getMessage(), e);
        }
    }

    /** The effective idle TTL in milliseconds. */
    static long ttlMs() {
        var raw = System.getProperty(TTL_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TTL_MS;
        }
        try {
            var parsed = Long.parseLong(raw.trim());
            if (parsed > 0) {
                return parsed;
            }
            logger.warn("Ignoring non-positive {}={} — using default {} ms", TTL_PROPERTY, raw, DEFAULT_TTL_MS);
        } catch (NumberFormatException e) {
            logger.warn("Ignoring malformed {}={} — using default {} ms", TTL_PROPERTY, raw, DEFAULT_TTL_MS);
        }
        return DEFAULT_TTL_MS;
    }
}
