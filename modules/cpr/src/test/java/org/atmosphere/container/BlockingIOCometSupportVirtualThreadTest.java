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
package org.atmosphere.container;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Whether {@link BlockingIOCometSupport} can back a suspended connection with a
 * virtual thread: each suspended request parks its own thread on a latch, so it
 * scales only if that park unmounts the virtual thread instead of pinning its
 * carrier. The tests suspend far more requests than there are carriers — a
 * pinned park would stall the rest before they ever reach suspend — and check
 * the resume, timeout and cancel exits on virtual threads.
 */
class BlockingIOCometSupportVirtualThreadTest {

    // Well above the default carrier count and the scheduler's 256 max pool,
    // so pinned parks could not all be absorbed by carrier compensation.
    private static final int SUSPENDED = 1_000;

    private AtmosphereFramework framework;
    private final ConcurrentLinkedQueue<AtmosphereResource> live = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "vt";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.emptyEnumeration();
            }
        });
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    private void handler(long suspendTimeoutMs, CountDownLatch suspended, AtomicInteger timedOut) {
        framework.addAtmosphereHandler("/vt", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource r) {
                r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                    @Override
                    public void onSuspend(AtmosphereResourceEvent event) {
                        live.add(event.getResource());
                        suspended.countDown();
                    }

                    @Override
                    public void onResume(AtmosphereResourceEvent event) {
                        if (event.isResumedOnTimeout()) {
                            timedOut.incrementAndGet();
                        }
                    }
                });
                r.suspend(suspendTimeoutMs);
            }

            @Override
            public void destroy() {
            }
        });
    }

    private List<Thread> dispatchOnVirtualThreads(int count, AtomicInteger finished) {
        var threads = new ArrayList<Thread>(count);
        for (int i = 0; i < count; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    framework.doCometSupport(new AtmosphereRequestImpl.Builder().pathInfo("/vt").build(),
                            AtmosphereResponseImpl.newInstance());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    finished.incrementAndGet();
                }
            }));
        }
        return threads;
    }

    private static void joinAll(List<Thread> threads, long timeoutMs) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (var t : threads) {
            var left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0 || !t.join(java.time.Duration.ofMillis(left))) {
                break;
            }
        }
    }

    @Test
    void manyMoreSuspendedRequestsThanCarriersAllParkAndResume() throws Exception {
        var suspended = new CountDownLatch(SUSPENDED);
        var finished = new AtomicInteger();
        handler(-1, suspended, new AtomicInteger());

        var threads = dispatchOnVirtualThreads(SUSPENDED, finished);

        assertTrue(suspended.await(30, TimeUnit.SECONDS),
                "only " + (SUSPENDED - suspended.getCount()) + "/" + SUSPENDED
                        + " requests reached suspend: parked virtual threads are pinning carriers");
        assertEquals(0, finished.get(), "a suspended request must stay parked until resumed");

        live.forEach(AtmosphereResource::resume);
        joinAll(threads, 30_000);
        assertEquals(SUSPENDED, finished.get(), "every resumed request must release its thread");
    }

    @Test
    void suspendTimeoutReleasesTheVirtualThread() throws Exception {
        var suspended = new CountDownLatch(50);
        var finished = new AtomicInteger();
        var timedOut = new AtomicInteger();
        handler(200, suspended, timedOut);

        var threads = dispatchOnVirtualThreads(50, finished);
        joinAll(threads, 10_000);

        assertEquals(50, finished.get(), "a timed-out suspend must release its thread");
        assertEquals(50, timedOut.get(), "each release must be reported as a timeout");
    }

    @Test
    void cancelReleasesAParkedVirtualThread() throws Exception {
        var suspended = new CountDownLatch(20);
        var finished = new AtomicInteger();
        handler(-1, suspended, new AtomicInteger());

        var threads = dispatchOnVirtualThreads(20, finished);
        assertTrue(suspended.await(10, TimeUnit.SECONDS));

        var async = (BlockingIOCometSupport) framework.getAsyncSupport();
        for (var r : live) {
            async.cancelled(r.getRequest(), r.getResponse());
        }
        joinAll(threads, 10_000);
        assertEquals(20, finished.get(), "cancel must release every parked thread");
    }
}
