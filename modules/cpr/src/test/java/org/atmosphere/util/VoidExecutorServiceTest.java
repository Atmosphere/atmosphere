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

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoidExecutorServiceTest {

    // ── Singleton ──

    @Test
    void voidConstantIsNotNull() {
        assertSame(VoidExecutorService.VOID, VoidExecutorService.VOID);
    }

    // ── execute runs synchronously ──

    @Test
    void executeRunsRunnableOnCallingThread() {
        var threadRef = new AtomicBoolean(false);
        var executor = new VoidExecutorService();
        var callerThread = Thread.currentThread();
        executor.execute(() -> threadRef.set(Thread.currentThread() == callerThread));
        assertTrue(threadRef.get());
    }

    // ── submit(Runnable) ──

    @Test
    void submitRunnableRunsSynchronously() {
        var counter = new AtomicInteger(0);
        var executor = new VoidExecutorService();
        var result = executor.submit(counter::incrementAndGet);
        assertNull(result);
        assertEquals(1, counter.get());
    }

    // ── submit(Runnable, result) ──

    @Test
    void submitRunnableWithResultRunsSynchronously() {
        var counter = new AtomicInteger(0);
        var executor = new VoidExecutorService();
        var result = executor.submit(counter::incrementAndGet, "done");
        assertNull(result);
        assertEquals(1, counter.get());
    }

    // ── submit(Callable) ──

    @Test
    void submitCallableRunsSynchronously() {
        var executor = new VoidExecutorService();
        var result = executor.submit(() -> 42);
        assertNull(result);
    }

    @Test
    void submitCallableSwallowsException() {
        var executor = new VoidExecutorService();
        // Should not throw even when callable throws
        assertNull(executor.submit((Callable<String>) () -> {
            throw new RuntimeException("boom");
        }));
    }

    // ── Lifecycle methods are no-ops ──

    @Test
    void shutdownIsNoOp() {
        var executor = new VoidExecutorService();
        executor.shutdown(); // should not throw
    }

    @Test
    void shutdownNowReturnsNull() {
        var executor = new VoidExecutorService();
        assertNull(executor.shutdownNow());
    }

    @Test
    void isShutdownReturnsFalse() {
        assertFalse(new VoidExecutorService().isShutdown());
    }

    @Test
    void isTerminatedReturnsFalse() {
        assertFalse(new VoidExecutorService().isTerminated());
    }

    @Test
    void awaitTerminationReturnsFalse() throws Exception {
        assertFalse(new VoidExecutorService().awaitTermination(1, TimeUnit.SECONDS));
    }

    // ── Unsupported batch operations ──

    @Test
    void invokeAllThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> new VoidExecutorService().invokeAll(java.util.List.of()));
    }

    @Test
    void invokeAllTimedThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> new VoidExecutorService().invokeAll(java.util.List.of(), 1, TimeUnit.SECONDS));
    }

    @Test
    void invokeAnyThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> new VoidExecutorService().invokeAny(java.util.List.of()));
    }

    @Test
    void invokeAnyTimedThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> new VoidExecutorService().invokeAny(java.util.List.of(), 1, TimeUnit.SECONDS));
    }

    private void assertTrue(boolean value) {
        org.junit.jupiter.api.Assertions.assertTrue(value);
    }
}
