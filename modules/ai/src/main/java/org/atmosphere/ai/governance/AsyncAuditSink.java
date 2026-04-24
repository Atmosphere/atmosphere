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
package org.atmosphere.ai.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a blocking {@link AuditSink} (Kafka producer, JDBC writer, SIEM
 * HTTP client) so the admission path never blocks on persistence.
 *
 * <h2>Delivery semantics</h2>
 * <ul>
 *   <li>Bounded in-memory queue (default {@code capacity = 1000}).</li>
 *   <li>Single background daemon thread drains the queue into the delegate.</li>
 *   <li>On queue-full, entries are <b>dropped</b> and the drop count is
 *       exposed via {@link #droppedCount()} — operators wire this into
 *       a Micrometer gauge to alert on chronic overload. This honors the
 *       Backpressure invariant (#3 in CLAUDE.md) — we never silently block,
 *       we never silently buffer unbounded.</li>
 *   <li>On delegate failure, the error is logged and the next entry is
 *       attempted; there is no retry queue (that's the delegate's job).</li>
 * </ul>
 */
public final class AsyncAuditSink implements AuditSink {

    private static final Logger logger = LoggerFactory.getLogger(AsyncAuditSink.class);

    private final AuditSink delegate;
    private final BlockingQueue<AuditEntry> queue;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    public AsyncAuditSink(AuditSink delegate, int capacity) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = Thread.ofVirtual()
                .name("atmosphere-audit-sink-" + delegate.name())
                .start(this::drain);
    }

    @Override
    public void write(AuditEntry entry) {
        if (entry == null) return;
        // offer() returns false when the queue is full — count the drop
        // and move on. Never block the admission thread.
        if (!queue.offer(entry)) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                logger.warn("AsyncAuditSink '{}' queue full — dropped {} entries so far",
                        delegate.name(), total);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            delegate.close();
        } catch (RuntimeException e) {
            logger.warn("Delegate sink '{}' failed to close: {}", delegate.name(), e.toString());
        }
    }

    @Override
    public String name() {
        return "async:" + delegate.name();
    }

    /** Number of entries dropped because the queue was full. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Current queue depth — for Micrometer gauge wiring. */
    public int queueDepth() {
        return queue.size();
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                var entry = queue.poll(500, TimeUnit.MILLISECONDS);
                if (entry == null) continue;
                try {
                    delegate.write(entry);
                } catch (RuntimeException e) {
                    logger.warn("Delegate sink '{}' failed: {}", delegate.name(), e.toString());
                }
            } catch (InterruptedException e) {
                // If shutting down, let the outer while check exit; otherwise
                // restore the flag so nested code sees the interrupt.
                if (!running) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
