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
package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * The once-per-resource dispatch of a handler's disconnect callback
 * ({@code @Disconnect}). The close thread and a broadcast in flight can both
 * hand the handler the cancelled event: the first to {@link #claim()} runs the
 * callback. The resource must stay whole while that callback runs, so the
 * close thread, when it lost the claim to a broadcast thread, waits in
 * {@link #awaitDispatch()} before it tears the resource and its event down.
 */
final class DisconnectDispatch {

    private static final Logger logger = LoggerFactory.getLogger(DisconnectDispatch.class);

    /** How long a teardown waits for a disconnect callback running on another thread. */
    static final long MAX_WAIT_MS = 5_000;

    private boolean claimed;
    private Thread runner;

    /** @return true if this call claimed the dispatch, false if it was already claimed */
    synchronized boolean claim() {
        if (claimed) {
            return false;
        }
        claimed = true;
        runner = Thread.currentThread();
        return true;
    }

    /** The claimed callback has returned (or thrown). */
    synchronized void done() {
        runner = null;
        notifyAll();
    }

    /**
     * Wait, at most {@link #MAX_WAIT_MS}, for a disconnect callback another thread
     * is running. The thread running the callback never waits for itself: a
     * callback that closes its own resource tears it down at once.
     */
    synchronized void awaitDispatch() {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MS);
        while (runner != null && runner != Thread.currentThread()) {
            var left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) {
                logger.warn("Disconnect callback still running on {} after {} ms; tearing the resource down",
                        runner.getName(), MAX_WAIT_MS);
                return;
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Interrupted waiting for a disconnect callback", e);
                return;
            }
        }
    }

    /** A recycled resource starts over. */
    synchronized void reset() {
        claimed = false;
        runner = null;
        notifyAll();
    }
}
