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
package org.atmosphere.checkpoint.temporal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry the activities use to reach the live {@link ExecutionSession} of a
 * run. Bounded by construction: an entry exists only while its
 * {@code TemporalDurableExecutionProvider.run()} invocation is in flight —
 * the provider registers before starting the Temporal workflow and removes in
 * a {@code finally} on every terminal path (Correctness Invariants #2, #3).
 */
final class ExecutionSessionRegistry {

    private static final ConcurrentMap<String, ExecutionSession<?>> SESSIONS = new ConcurrentHashMap<>();

    private ExecutionSessionRegistry() {
    }

    static void register(String executionId, ExecutionSession<?> session) {
        SESSIONS.put(executionId, session);
    }

    static Optional<ExecutionSession<?>> get(String executionId) {
        return Optional.ofNullable(SESSIONS.get(executionId));
    }

    static void remove(String executionId) {
        SESSIONS.remove(executionId);
    }
}
