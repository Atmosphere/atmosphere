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
package org.atmosphere.admin;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ring buffer that records control plane write operations for
 * audit purposes. Follows the same pattern as
 * {@code InMemoryCoordinationJournal}.
 *
 * @since 4.0
 */
public final class ControlAuditLog {

    private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int maxEntries;

    /**
     * A single audit log entry recording a control plane action.
     *
     * @param timestamp when the action was executed
     * @param principal who executed the action, or "anonymous"
     * @param action    the action name (e.g. "broadcast", "disconnect")
     * @param target    the target identifier (e.g. broadcaster ID, resource UUID)
     * @param success   whether the action succeeded
     * @param message   optional result message
     */
    public record AuditEntry(Instant timestamp, String principal, String action,
                             String target, boolean success, String message) {
    }

    public ControlAuditLog(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Record a control action.
     */
    public void record(String principal, String action, String target,
                       boolean success, String message) {
        var entry = new AuditEntry(
                Instant.now(),
                principal != null ? principal : "anonymous",
                action,
                target,
                success,
                message);
        entries.addLast(entry);
        if (size.incrementAndGet() > maxEntries) {
            entries.pollFirst();
            size.decrementAndGet();
        }
    }

    /**
     * Return all audit entries, most recent last.
     */
    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Return the most recent {@code limit} entries.
     */
    public List<AuditEntry> entries(int limit) {
        var all = List.copyOf(entries);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }
}
