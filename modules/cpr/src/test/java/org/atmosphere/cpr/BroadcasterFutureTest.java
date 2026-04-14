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

import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcasterFutureTest {

    // ── Basic construction and done ──

    @Test
    void getReturnsMessageAfterDone() throws Exception {
        var future = new BroadcasterFuture<>("hello");
        future.done();
        assertEquals("hello", future.get());
    }

    @Test
    void isDoneFalseBeforeDone() {
        var future = new BroadcasterFuture<>("msg");
        assertFalse(future.isDone());
    }

    @Test
    void isDoneTrueAfterDone() {
        var future = new BroadcasterFuture<>("msg");
        future.done();
        assertTrue(future.isDone());
    }

    @Test
    void isCancelledFalseByDefault() {
        var future = new BroadcasterFuture<>("msg");
        assertFalse(future.isCancelled());
    }

    @Test
    void cancelSetsCancelled() {
        var future = new BroadcasterFuture<>("msg");
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
    }

    // ── Timed get ──

    @Test
    void getWithTimeoutReturnsAfterDone() throws Exception {
        var future = new BroadcasterFuture<>("result");
        future.done();
        assertEquals("result", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void getWithTimeoutThrowsOnExpiry() {
        var future = new BroadcasterFuture<>("msg");
        assertThrows(TimeoutException.class, () -> future.get(10, TimeUnit.MILLISECONDS));
    }

    // ── Latch count > 1 ──

    @Test
    void multiLatchRequiresMultipleDoneCalls() {
        var future = new BroadcasterFuture<>("msg", 3);
        future.done();
        assertFalse(future.isDone());
        future.done();
        assertFalse(future.isDone());
        future.done();
        assertTrue(future.isDone());
    }

    // ── Cancel unblocks get ──

    @Test
    void cancelUnblocksGet() throws Exception {
        var future = new BroadcasterFuture<>("msg");
        // Cancel releases the latch
        future.cancel(false);
        assertEquals("msg", future.get(100, TimeUnit.MILLISECONDS));
    }

    // ── Inner future delegation ──

    @Test
    @SuppressWarnings("unchecked")
    void innerFutureDelegatesCancel() {
        var inner = mock(Future.class);
        when(inner.cancel(true)).thenReturn(true);
        var future = new BroadcasterFuture<>(inner, "msg");
        assertTrue(future.cancel(true));
        verify(inner).cancel(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void innerFutureDelegatesIsCancelled() {
        var inner = mock(Future.class);
        when(inner.isCancelled()).thenReturn(true);
        var future = new BroadcasterFuture<>(inner, "msg");
        assertTrue(future.isCancelled());
    }

    @Test
    @SuppressWarnings("unchecked")
    void innerFutureDelegatesIsDone() {
        var inner = mock(Future.class);
        when(inner.isDone()).thenReturn(true);
        var future = new BroadcasterFuture<>(inner, "msg");
        assertTrue(future.isDone());
    }

    @Test
    @SuppressWarnings("unchecked")
    void innerFutureDelegatesGet() throws Exception {
        var inner = mock(Future.class);
        when(inner.get()).thenReturn("inner-result");
        var future = new BroadcasterFuture<>(inner, "msg");
        assertEquals("inner-result", future.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void innerFutureDelegatesTimedGet() throws Exception {
        var inner = mock(Future.class);
        when(inner.get(5L, TimeUnit.SECONDS)).thenReturn("timed");
        var future = new BroadcasterFuture<>(inner, "msg");
        assertEquals("timed", future.get(5, TimeUnit.SECONDS));
    }

    // ── Thread safety: done() from another thread ──

    @Test
    void getBlocksUntilDoneFromOtherThread() throws Exception {
        var future = new BroadcasterFuture<>("threaded");
        var thread = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            future.done();
        });
        thread.start();
        assertEquals("threaded", future.get(2, TimeUnit.SECONDS));
        thread.join();
    }

    // ── Done returns self for fluent API ──

    @Test
    void doneReturnsSelf() {
        var future = new BroadcasterFuture<>("msg");
        var result = future.done();
        assertEquals(future, result);
    }
}
