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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a task's lifecycle position. Promoted from a nested record on
 * {@link Task} to a top-level type in v1.0.0; the {@code timestamp} field
 * (ISO-8601) was added so consumers can dedupe / order out-of-order events,
 * and {@code message} is now a {@link Message} (was a free String pre-1.0).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatus(TaskState state, Message message, String timestamp) {

    public TaskStatus(TaskState state, Message message) {
        this(state, message, Instant.now().toString());
    }

    public static TaskStatus of(TaskState state, String text) {
        return new TaskStatus(state,
                text == null ? null : new Message(
                        java.util.UUID.randomUUID().toString(), null, null,
                        Role.AGENT, List.of(Part.text(text)), null, null, null),
                Instant.now().toString());
    }

    public static TaskStatus of(TaskState state) {
        return new TaskStatus(state, null, Instant.now().toString());
    }
}
