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

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pending cancel of a detached run fires once when its grace expires,
 * never after a reattach rescinded it or after the run ended, and never twice.
 */
class RunDisconnectGraceTest {

    @AfterEach
    void tearDown() {
        RunDisconnectGrace.shutdown();
    }

    @Test
    void theCancelFiresOnceWhenTheGraceExpires() throws Exception {
        var fired = new CountDownLatch(1);
        var count = new AtomicInteger();
        assertTrue(RunDisconnectGrace.detach("run-expire", Duration.ofMillis(50), () -> {
            count.incrementAndGet();
            fired.countDown();
        }));
        assertTrue(RunDisconnectGrace.isDetached("run-expire"));
        assertTrue(fired.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, count.get());
        assertFalse(RunDisconnectGrace.isDetached("run-expire"));
        assertFalse(RunDisconnectGrace.reattached("run-expire"), "too late to rescind an expired grace");
    }

    @Test
    void aReattachRescindsTheCancelAndItNeverFiresLate() throws Exception {
        var count = new AtomicInteger();
        RunDisconnectGrace.detach("run-back", Duration.ofMillis(100), count::incrementAndGet);
        assertTrue(RunDisconnectGrace.reattached("run-back"));
        Thread.sleep(300);
        assertEquals(0, count.get(), "a rescinded cancel must not fire after the grace");
        RunDisconnectGrace.runEnded("run-back");
        assertEquals(0, RunDisconnectGrace.size(), "an ended run is forgotten");
    }

    @Test
    void aRunThatEndsWithinTheGraceIsNeverCancelled() throws Exception {
        var count = new AtomicInteger();
        RunDisconnectGrace.detach("run-done", Duration.ofMillis(100), count::incrementAndGet);
        RunDisconnectGrace.runEnded("run-done");
        Thread.sleep(300);
        assertEquals(0, count.get());
        assertFalse(RunDisconnectGrace.redetach("run-done", Duration.ofMillis(10)),
                "an ended run cannot be detached again");
    }

    @Test
    void aReattachedClientThatLosesItsConnectionDetachesTheRunAgain() throws Exception {
        var fired = new CountDownLatch(1);
        RunDisconnectGrace.detach("run-again", Duration.ofMillis(100), fired::countDown);
        assertTrue(RunDisconnectGrace.reattached("run-again"));
        assertTrue(RunDisconnectGrace.redetach("run-again", Duration.ofMillis(50)));
        assertTrue(fired.await(5, TimeUnit.SECONDS), "the second grace expires with the first action");
    }

    @Test
    void aReattachedClientThatLeavesOnPurposeCancelsAtOnce() {
        var count = new AtomicInteger();
        RunDisconnectGrace.detach("run-leave", Duration.ofMinutes(5), count::incrementAndGet);
        RunDisconnectGrace.reattached("run-leave");
        assertTrue(RunDisconnectGrace.expireNow("run-leave"));
        assertFalse(RunDisconnectGrace.expireNow("run-leave"), "the cancel runs once");
        assertEquals(1, count.get());
    }

    @Test
    void aZeroGraceDetachesNothing() {
        assertFalse(RunDisconnectGrace.detach("run-zero", Duration.ZERO, () -> { }));
        assertFalse(RunDisconnectGrace.isDetached("run-zero"));
    }

    @Test
    void shutdownCancelsEveryRunStillDetached() {
        var count = new AtomicInteger();
        RunDisconnectGrace.detach("run-a", Duration.ofMinutes(5), count::incrementAndGet);
        RunDisconnectGrace.detach("run-b", Duration.ofMinutes(5), count::incrementAndGet);
        RunDisconnectGrace.shutdown();
        assertEquals(2, count.get(), "no run is left waiting for a timer that stopped");
        assertEquals(0, RunDisconnectGrace.size());
    }

    @Test
    void aReattachRacingTheExpiryEitherRescindsOrFiresNeverBoth() throws Exception {
        for (int i = 0; i < 300; i++) {
            var runId = "race-" + i;
            var fired = new AtomicInteger();
            RunDisconnectGrace.detach(runId, Duration.ofMillis(1), fired::incrementAndGet);
            var start = new CountDownLatch(1);
            var rescinded = new boolean[1];
            var reattach = Thread.startVirtualThread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                rescinded[0] = RunDisconnectGrace.reattached(runId);
            });
            start.countDown();
            reattach.join();
            // Long enough for a timer that was not rescinded to have fired.
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!rescinded[0] && fired.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            Thread.sleep(3);
            assertEquals(rescinded[0] ? 0 : 1, fired.get(),
                    "iteration " + i + ": rescinded=" + rescinded[0] + " fired=" + fired.get());
            RunDisconnectGrace.runEnded(runId);
        }
    }

    @Test
    void onlyALostTransportIsGrantedTheGrace() {
        assertTrue(RunDisconnectGrace.isTransportLoss(event(true, false, false, -1)),
                "a cancelled connection (lost network, idle eviction) may come back");
        assertTrue(RunDisconnectGrace.isTransportLoss(event(false, true, false, 1001)),
                "a WebSocket closed going-away is a page leaving or reloading");
        assertFalse(RunDisconnectGrace.isTransportLoss(event(false, true, false, 1000)),
                "a WebSocket closed normally (atmosphere.js disconnect) left on purpose");
        assertFalse(RunDisconnectGrace.isTransportLoss(event(false, true, false, -1)),
                "an HTTP transport's close message is a client leaving");
        assertFalse(RunDisconnectGrace.isTransportLoss(event(true, false, true, -1)),
                "a close the application made is not waited for");
    }

    private static AtmosphereResourceEvent event(boolean cancelled, boolean closedByClient,
                                                 boolean closedByApplication, int closeCode) {
        var event = mock(AtmosphereResourceEventImpl.class);
        when(event.isCancelled()).thenReturn(cancelled);
        when(event.isClosedByClient()).thenReturn(closedByClient);
        when(event.isClosedByApplication()).thenReturn(closedByApplication);
        when(event.webSocketCloseCode()).thenReturn(closeCode);
        return event;
    }
}
