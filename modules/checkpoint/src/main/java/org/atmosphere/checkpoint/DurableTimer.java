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
package org.atmosphere.checkpoint;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A wall-clock timer that survives process restart: it is persisted in a
 * {@link DurableTimerStore} and fired by {@link DurableTimerService} once its
 * {@link #fireAt} has passed — including a fire that becomes due while the
 * process was down (the service re-arms from the store on start).
 *
 * @param id      stable identifier (also the dedupe key in the store)
 * @param fireAt  the wall-clock instant at/after which the timer fires
 * @param kind    routes the firing to a registered callback (e.g.
 *                {@code "approval-auto-reject"}); never {@code null}
 * @param payload small string-keyed context the callback needs (e.g. the
 *                approval id to deny); never {@code null}
 */
public record DurableTimer(String id, Instant fireAt, String kind, Map<String, String> payload) {

    public DurableTimer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("timer id must not be blank");
        }
        Objects.requireNonNull(fireAt, "fireAt");
        kind = kind != null ? kind : "";
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }

    /** @return {@code true} when {@code now} is at or after {@link #fireAt}. */
    public boolean isDue(Instant now) {
        return now != null && !now.isBefore(fireAt);
    }

    /** Convenience for a payload-less timer. */
    public static DurableTimer of(String id, Instant fireAt, String kind) {
        return new DurableTimer(id, fireAt, kind, Map.of());
    }
}
