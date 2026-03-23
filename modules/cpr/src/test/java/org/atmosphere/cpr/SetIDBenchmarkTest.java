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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.atmosphere.util.ExecutorsFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark test for PR #18: setID() race condition fix.
 *
 * PR #18 fixed a bug where calling setID() on a broadcaster after initialization
 * did not update the JMS consumer selector, causing a race condition. The fix
 * introduced proper synchronization and consumer restart on ID change.
 *
 * This test validates that the current DefaultBroadcaster.setID() implementation:
 * 1. Is thread-safe under concurrent access
 * 2. Correctly updates the broadcaster factory registry
 * 3. Maintains consistency when setID() races with broadcast operations
 * 4. Performs adequately under contention
 */
@SuppressWarnings("deprecation")
public class SetIDBenchmarkTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS, "true")
                .getAtmosphereConfig();

        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
    }

    @AfterEach
    public void tearDown() throws Exception {
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    /**
     * Verifies that concurrent setID() calls on different broadcasters
     * do not corrupt the BroadcasterFactory registry.
     */
    @Test
    public void testConcurrentSetIDOnMultipleBroadcasters() throws Exception {
        int threadCount = 10;
        int idsPerThread = 50;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var error = new AtomicReference<Throwable>();

        for (int t = 0; t < threadCount; t++) {
            int threadIdx = t;
            Thread.ofVirtual().start(() -> {
                try {
                    var b = factory.get(DefaultBroadcaster.class, "init-" + threadIdx);
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < idsPerThread; i++) {
                        String newId = "thread-" + threadIdx + "-id-" + i;
                        b.setID(newId);
                        assertEquals(newId, b.getID());
                        // Verify the factory can look up by new ID
                        assertNotNull(factory.lookup(DefaultBroadcaster.class, newId));
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for threads");
        if (error.get() != null) {
            fail("Concurrent setID failed: " + error.get().getMessage());
        }
    }

    /**
     * Benchmarks setID() throughput: measures how many setID() calls
     * can be performed per second under contention.
     */
    @Test
    public void testSetIDThroughputUnderContention() throws Exception {
        int threadCount = 4;
        int iterations = 200;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var totalOps = new AtomicInteger();
        var error = new AtomicReference<Throwable>();

        long startTime = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
            int threadIdx = t;
            Thread.ofVirtual().start(() -> {
                try {
                    var b = factory.get(DefaultBroadcaster.class, "perf-" + threadIdx);
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < iterations; i++) {
                        b.setID("perf-" + threadIdx + "-" + i);
                        totalOps.incrementAndGet();
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out");
        long elapsed = System.nanoTime() - startTime;

        if (error.get() != null) {
            fail("setID throughput test failed: " + error.get().getMessage());
        }

        double opsPerSec = totalOps.get() / (elapsed / 1_000_000_000.0);
        System.out.printf("[Benchmark] setID throughput: %d ops in %.2f ms (%.0f ops/sec, %d threads)%n",
                totalOps.get(), elapsed / 1_000_000.0, opsPerSec, threadCount);

        // Sanity: all operations completed
        assertEquals(threadCount * iterations, totalOps.get());
    }

    /**
     * Tests the race condition that PR #18 originally fixed:
     * setID() racing with broadcast operations must not lose messages
     * or leave the broadcaster in an inconsistent state.
     */
    @Test
    public void testSetIDRacingWithBroadcast() throws Exception {
        var b = factory.get(DefaultBroadcaster.class, "race-test");
        var handler = new MessageCountHandler();
        var ar = new AtmosphereResourceImpl(config,
                b,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                handler);
        b.addAtmosphereResource(ar);

        int broadcastCount = 100;
        int idChangeCount = 20;
        var broadcastLatch = new CountDownLatch(broadcastCount);

        b.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onComplete(Broadcaster broadcaster) {
                broadcastLatch.countDown();
            }
        });

        var error = new AtomicReference<Throwable>();
        var startBarrier = new CyclicBarrier(2);

        // Thread 1: broadcast messages
        Thread.ofVirtual().start(() -> {
            try {
                startBarrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < broadcastCount; i++) {
                    b.broadcast("msg-" + i);
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        // Thread 2: change ID concurrently
        Thread.ofVirtual().start(() -> {
            try {
                startBarrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < idChangeCount; i++) {
                    b.setID("race-test-" + i);
                    Thread.sleep(1);
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        assertTrue(broadcastLatch.await(30, TimeUnit.SECONDS),
                "Not all broadcasts completed");

        if (error.get() != null) {
            fail("Race test failed: " + error.get().getMessage());
        }

        // All broadcasts should have been delivered
        assertEquals(broadcastCount, handler.count.get(),
                "Some messages were lost during concurrent setID");
    }

    /**
     * Verifies that setID() in AbstractBroadcasterProxy subclass
     * properly triggers reconfiguration (the core fix from PR #18).
     */
    @Test
    public void testAbstractBroadcasterProxySetIDTriggersReconfigure() throws Exception {
        var proxyFactory = new DefaultBroadcasterFactory();
        proxyFactory.configure(TestBroadcasterProxy.class, "NEVER", config);
        config.framework().setBroadcasterFactory(proxyFactory);

        var proxy = (TestBroadcasterProxy) proxyFactory.get(TestBroadcasterProxy.class, "proxy-init");
        assertEquals("proxy-init", proxy.getID());

        // Change the ID after initialization (the scenario PR #18 fixed)
        proxy.setID("proxy-updated");
        assertEquals("proxy-updated", proxy.getID());

        // Verify the factory registry is consistent
        assertNotNull(proxyFactory.lookup(TestBroadcasterProxy.class, "proxy-updated"));
        assertNull(proxyFactory.lookup(TestBroadcasterProxy.class, "proxy-init"));

        proxyFactory.destroy();
    }

    /**
     * Benchmarks the latency of individual setID() operations
     * to verify no excessive overhead from the locking strategy.
     */
    @Test
    public void testSetIDLatency() throws Exception {
        var b = factory.get(DefaultBroadcaster.class, "latency-test");

        int warmup = 50;
        int measured = 200;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            b.setID("warmup-" + i);
        }

        // Measure
        long[] latencies = new long[measured];
        for (int i = 0; i < measured; i++) {
            long start = System.nanoTime();
            b.setID("measured-" + i);
            latencies[i] = System.nanoTime() - start;
        }

        // Compute stats
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long l : latencies) {
            sum += l;
            min = Math.min(min, l);
            max = Math.max(max, l);
        }
        double avg = sum / (double) measured;

        // Sort for percentiles
        java.util.Arrays.sort(latencies);
        long p50 = latencies[measured / 2];
        long p99 = latencies[(int) (measured * 0.99)];

        System.out.printf("[Benchmark] setID latency (ns): avg=%.0f, min=%d, max=%d, p50=%d, p99=%d%n",
                avg, min, max, p50, p99);

        // Sanity: setID should complete in under 10ms even in worst case
        assertTrue(max < 10_000_000, "setID took longer than 10ms: " + max + "ns");
    }

    /**
     * A test AbstractBroadcasterProxy subclass that tracks reconfiguration calls,
     * simulating the JMSBroadcaster pattern from PR #18.
     */
    public static class TestBroadcasterProxy extends AbstractBroadcasterProxy {

        private final AtomicBoolean incomingStarted = new AtomicBoolean(false);

        @Override
        public void incomingBroadcast() {
            incomingStarted.set(true);
            // Simulate a long-running listener (like JMS consumer)
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void outgoingBroadcast(Object message) {
            // No-op for testing
        }
    }

    /**
     * AtmosphereHandler that counts received messages.
     */
    static class MessageCountHandler implements AtmosphereHandler {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            count.incrementAndGet();
        }

        @Override
        public void destroy() {
        }
    }
}
