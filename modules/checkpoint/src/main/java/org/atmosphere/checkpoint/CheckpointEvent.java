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

/**
 * Sealed lifecycle event emitted by a {@link CheckpointStore} when snapshots
 * are created, read, forked or deleted. Listeners registered via
 * {@link CheckpointStore#addListener(CheckpointListener)} receive these events.
 *
 * <p>Events are informational; failing to process one must not affect the
 * underlying store operation.</p>
 */
public sealed interface CheckpointEvent {

    CheckpointId checkpointId();

    Instant timestamp();

    record Saved(CheckpointId checkpointId, String coordinationId, Instant timestamp)
            implements CheckpointEvent {}

    record Loaded(CheckpointId checkpointId, String coordinationId, Instant timestamp)
            implements CheckpointEvent {}

    record Forked(CheckpointId checkpointId, CheckpointId sourceId, String coordinationId, Instant timestamp)
            implements CheckpointEvent {}

    record Deleted(CheckpointId checkpointId, String coordinationId, Instant timestamp)
            implements CheckpointEvent {}
}
