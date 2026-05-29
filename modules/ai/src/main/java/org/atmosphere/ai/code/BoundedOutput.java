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
package org.atmosphere.ai.code;

/**
 * A capped, append-only character buffer. Captured process output is bounded so
 * a chatty or runaway script cannot exhaust heap or flood the broadcaster
 * (Correctness Invariant #3 — Backpressure). Once the cap is reached, further
 * appends are dropped and {@link #truncated()} flips to {@code true}; the reader
 * keeps draining the underlying stream so the process never blocks on a full
 * pipe.
 *
 * <p>The cap is applied in characters, using {@code maxBytes} as the budget — a
 * deliberate, slightly conservative approximation for multi-byte text. Thread
 * safety is provided by synchronizing the mutators so a separate stderr-draining
 * thread can append concurrently.</p>
 */
final class BoundedOutput {

    private final int maxChars;
    private final StringBuilder sb = new StringBuilder();
    private boolean truncated;

    BoundedOutput(int maxBytes) {
        this.maxChars = Math.max(0, maxBytes);
    }

    synchronized void append(String chunk) {
        if (truncated || chunk == null || chunk.isEmpty()) {
            return;
        }
        int remaining = maxChars - sb.length();
        if (remaining <= 0) {
            truncated = true;
            return;
        }
        if (chunk.length() <= remaining) {
            sb.append(chunk);
        } else {
            sb.append(chunk, 0, remaining);
            truncated = true;
        }
    }

    synchronized boolean truncated() {
        return truncated;
    }

    @Override
    public synchronized String toString() {
        return sb.toString();
    }
}
