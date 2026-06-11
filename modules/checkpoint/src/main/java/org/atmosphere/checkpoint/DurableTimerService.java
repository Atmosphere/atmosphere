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
package org.atmosphere.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fires {@link DurableTimer}s when their wall-clock time arrives — including
 * timers that became due while the process was down: {@link #start()} polls the
 * {@link DurableTimerStore} so a "wake at T" or "auto-reject after 72h" timer
 * armed before a restart fires on the next poll after recovery (re-arm from
 * store). This is the wall-clock scheduler the framework deliberately lacked;
 * approval expiry was previously only a passive {@code expiresAt} check.
 *
 * <p>Firing is <strong>exactly-once</strong>: a timer is claimed via
 * {@link DurableTimerStore#remove(String)} before its callback runs, so
 * concurrent polls cannot double-fire. A callback that throws is logged, not
 * swallowed, and the timer is not retried (fire-once semantics).</p>
 *
 * <p>Ownership: the service creates and therefore shuts down its own scheduler
 * thread on {@link #close()} (Correctness Invariant #1).</p>
 */
public final class DurableTimerService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DurableTimerService.class);

    /** Default poll cadence. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private final DurableTimerStore store;
    private final Map<String, Consumer<DurableTimer>> callbacks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller;
    private final Clock clock;
    private final Duration pollInterval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DurableTimerService(DurableTimerStore store) {
        this(store, DEFAULT_POLL_INTERVAL, Clock.systemUTC());
    }

    public DurableTimerService(DurableTimerStore store, Duration pollInterval, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.pollInterval = pollInterval != null ? pollInterval : DEFAULT_POLL_INTERVAL;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "durable-timer-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /** Register the callback for a timer {@code kind}. */
    public void onFire(String kind, Consumer<DurableTimer> callback) {
        callbacks.put(kind, Objects.requireNonNull(callback, "callback"));
    }

    /** Arm a timer (persisted immediately so it survives restart). */
    public void schedule(DurableTimer timer) {
        store.save(Objects.requireNonNull(timer, "timer"));
    }

    /** Cancel an armed timer that has not yet fired. */
    public boolean cancel(String timerId) {
        return store.remove(timerId);
    }

    /** Start the background poller. Idempotent. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("DurableTimerService is closed");
        }
        if (started.compareAndSet(false, true)) {
            poller.scheduleWithFixedDelay(this::pollSafely, 0,
                    pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Fire every due timer once. Exposed (package-private) so tests drive it
     * deterministically with an injected {@link Clock} instead of waiting on the
     * scheduler.
     *
     * @return the number of timers fired this pass
     */
    int poll() {
        var now = clock.instant();
        var fired = 0;
        for (var timer : store.all()) {
            if (timer.isDue(now) && store.remove(timer.id())) {
                fire(timer);
                fired++;
            }
        }
        return fired;
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException e) {
            logger.error("Durable-timer poll failed", e);
        }
    }

    private void fire(DurableTimer timer) {
        var callback = callbacks.get(timer.kind());
        if (callback == null) {
            logger.warn("Durable timer {} fired but no callback is registered for kind '{}'",
                    timer.id(), timer.kind());
            return;
        }
        try {
            callback.accept(timer);
        } catch (RuntimeException e) {
            logger.error("Durable timer {} callback (kind={}) failed", timer.id(), timer.kind(), e);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        poller.shutdownNow();
    }
}
