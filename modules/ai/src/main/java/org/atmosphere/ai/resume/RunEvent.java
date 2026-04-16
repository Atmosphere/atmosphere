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
package org.atmosphere.ai.resume;

import java.time.Instant;

/**
 * One replayable event from an in-flight agent run. Captured by
 * {@link RunEventReplayBuffer} and replayed when a client reconnects with
 * a matching {@code runId}.
 *
 * @param sequence monotonic sequence number assigned on capture; drives
 *                 replay ordering and the "resume from here" contract
 * @param type     a short type tag, e.g. {@code "token"}, {@code "toolCall"},
 *                 {@code "error"}, {@code "complete"} — mirrors the public
 *                 {@code AiEvent} variants
 * @param payload  opaque payload string, typically JSON-serialized for wire
 *                 replay
 * @param timestamp when the event was captured
 */
public record RunEvent(long sequence, String type, String payload, Instant timestamp) {

    public RunEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (payload == null) {
            payload = "";
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
    }
}
