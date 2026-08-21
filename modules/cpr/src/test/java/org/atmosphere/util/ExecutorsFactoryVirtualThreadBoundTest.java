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
package org.atmosphere.util;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#7): with {@code USE_VIRTUAL_THREADS} on (the
 * default), the documented thread-pool bounds were silently ignored — an
 * operator who bounded write concurrency for backpressure got unbounded
 * concurrency instead. An explicitly configured bound must be honored in
 * virtual-thread mode.
 */
class ExecutorsFactoryVirtualThreadBoundTest {

    private AtmosphereFramework framework;
    private ExecutorService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownNow();
        }
        if (framework != null) {
            framework.destroy();
        }
    }

    private AtmosphereFramework framework(String maxProcessingThreads) throws Exception {
        framework = new AtmosphereFramework();
        if (maxProcessingThreads != null) {
            framework.addInitParameter(
                    ApplicationConfig.BROADCASTER_MESSAGE_PROCESSING_THREADPOOL_MAXSIZE,
                    maxProcessingThreads);
        }
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init();
        return framework;
    }

    @Test
    void explicitBoundIsHonoredInVirtualThreadMode() throws Exception {
        var fw = framework("2");
        service = ExecutorsFactory.getMessageDispatcher(fw.getAtmosphereConfig(), "bound-test");

        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var release = new CountDownLatch(1);
        var allDone = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            service.execute(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                    allDone.countDown();
                }
            });
        }
        // Give the first two workers time to start; the other two must queue.
        Thread.sleep(300);
        release.countDown();
        assertTrue(allDone.await(10, TimeUnit.SECONDS), "all tasks must eventually run");
        assertEquals(2, peak.get(),
                "an explicitly configured bound of 2 must cap concurrency in "
                + "virtual-thread mode — before the fix all 4 ran at once");
    }

    @Test
    void withoutAnExplicitBoundVirtualThreadsStayUnbounded() throws Exception {
        var fw = framework(null);
        service = ExecutorsFactory.getMessageDispatcher(fw.getAtmosphereConfig(), "unbound-test");

        var started = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            service.execute(() -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(started.await(10, TimeUnit.SECONDS),
                "with no explicit bound, virtual-thread-per-task must start every task");
        release.countDown();
    }
}
