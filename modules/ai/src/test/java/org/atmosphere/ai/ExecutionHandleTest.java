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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 contract: {@link ExecutionHandle#completed()} is a terminal-state
 * handle (used by the {@code AgentRuntime.executeWithHandle} default), and
 * {@link ExecutionHandle.Settable} is the reusable helper runtimes can wrap
 * around a native cancel primitive.
 */
class ExecutionHandleTest {

    @Test
    void completedHandleIsAlreadyDone() {
        var handle = ExecutionHandle.completed();
        assertTrue(handle.isDone());
        assertTrue(handle.whenDone().isDone());
        // Cancel on an already-done handle must be a no-op.
        handle.cancel();
        assertTrue(handle.isDone());
    }

    @Test
    void settableCancelFiresNativeExactlyOnce() {
        var fires = new AtomicInteger();
        var handle = new ExecutionHandle.Settable(fires::incrementAndGet);

        assertFalse(handle.isDone());
        assertFalse(handle.isCancelled());

        handle.cancel();
        assertEquals(1, fires.get());
        assertTrue(handle.isCancelled());
        assertTrue(handle.isDone());

        // Idempotent — subsequent cancels must not re-fire the native primitive.
        handle.cancel();
        handle.cancel();
        assertEquals(1, fires.get(), "Settable.cancel must fire its native runnable at most once");
    }

    @Test
    void settableCompleteResolvesWhenDoneWithoutCancelFlag() {
        var handle = new ExecutionHandle.Settable(() -> { throw new AssertionError("native cancel must not run on normal completion"); });
        handle.complete();
        assertTrue(handle.isDone());
        assertFalse(handle.isCancelled());
        assertTrue(handle.whenDone().isDone());
    }

    @Test
    void settableCompleteExceptionallyPropagatesToWhenDone() {
        var handle = new ExecutionHandle.Settable(null);
        handle.completeExceptionally(new RuntimeException("boom"));
        assertTrue(handle.whenDone().isCompletedExceptionally());
        assertTrue(handle.isDone());
    }

    @Test
    void settableWithNullNativeCancelJustSetsFlag() {
        var handle = new ExecutionHandle.Settable(null);
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertTrue(handle.isDone());
    }
}
