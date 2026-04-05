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

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identifier for a {@link WorkflowSnapshot}. Wraps an opaque string so
 * storage backends are free to pick their own format (UUID, ULID, database
 * sequence, etc.).
 *
 * <p>Two checkpoints are equal iff their string values are equal.</p>
 */
public record CheckpointId(String value) {

    public CheckpointId {
        Objects.requireNonNull(value, "CheckpointId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CheckpointId value must not be blank");
        }
    }

    /** Generate a fresh random checkpoint id backed by a UUIDv4. */
    public static CheckpointId random() {
        return new CheckpointId(UUID.randomUUID().toString());
    }

    /** Wrap an existing string value. */
    public static CheckpointId of(String value) {
        return new CheckpointId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
