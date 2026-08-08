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
package org.atmosphere.ai.batch;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A durable batch job: one submission of N independent LLM requests
 * dispatched through a governed {@code AiPipeline}. Per-item failures never
 * fail the job — a job whose every item was processed is {@code COMPLETED}
 * and the counts expose how many items succeeded, failed, or were cancelled.
 *
 * @param id             the job id ({@code batch-<hex>})
 * @param agent          the registered agent / endpoint serving name
 * @param submitter      caller-supplied submitter label; may be empty
 * @param status         current job status
 * @param createdAt      submission time
 * @param updatedAt      last state-change time
 * @param totalItems     items submitted
 * @param succeededItems items that produced an output
 * @param failedItems    items that failed, timed out, or were blocked
 * @param cancelledItems items cancelled before completion
 * @param error          job-level error detail; empty unless {@code FAILED}
 */
public record BatchJob(
        String id,
        String agent,
        String submitter,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        int totalItems,
        int succeededItems,
        int failedItems,
        int cancelledItems,
        String error) {

    /** Job lifecycle states; the last three are terminal. */
    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

        /** Whether this status is terminal (no further transitions). */
        public boolean terminal() {
            return switch (this) {
                case COMPLETED, FAILED, CANCELLED -> true;
                case QUEUED, RUNNING -> false;
            };
        }

        /** Lower-case wire / storage form. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parse the lower-case wire / storage form. */
        public static Status fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public BatchJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        submitter = submitter != null ? submitter : "";
        error = error != null ? error : "";
    }

    /** Items not yet in a terminal state. */
    public int pendingItems() {
        return totalItems - succeededItems - failedItems - cancelledItems;
    }
}
