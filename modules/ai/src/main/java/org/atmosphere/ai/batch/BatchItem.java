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

import java.util.Locale;
import java.util.Objects;

/**
 * One item of a {@link BatchJob}: a single LLM request dispatched through the
 * job's governed pipeline, with its own terminal outcome.
 *
 * @param index    zero-based position within the job (stable result order)
 * @param customId caller-supplied correlation id (defaults to the index)
 * @param input    the user message dispatched to the pipeline
 * @param status   current item status
 * @param output   the pipeline's response text; empty unless {@code SUCCEEDED}
 * @param error    failure detail; empty unless {@code FAILED} / {@code CANCELLED}
 */
public record BatchItem(
        int index,
        String customId,
        String input,
        Status status,
        String output,
        String error) {

    /** Item lifecycle states; all but {@code PENDING} are terminal. */
    public enum Status {
        PENDING, SUCCEEDED, FAILED, CANCELLED;

        /** Whether this status is terminal. */
        public boolean terminal() {
            return this != PENDING;
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

    public BatchItem {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        Objects.requireNonNull(customId, "customId");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(status, "status");
        output = output != null ? output : "";
        error = error != null ? error : "";
    }
}
