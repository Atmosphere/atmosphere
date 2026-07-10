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
package org.atmosphere.ai.tape;

import java.util.Objects;

/**
 * One appended step of a {@link TapeRun}, keyed by {@code (runId, seq)}.
 * {@code seq} is assigned by the tape writer and is monotonic per run —
 * monotonic, not necessarily dense (a step whose store append failed
 * consumes its seq).
 *
 * <p>{@code kind} is the wire event type for typed {@code AiEvent} steps
 * (e.g. {@code tool-start}), or one of the tape's own kinds: {@code text}
 * (a coalesced text segment), {@code metadata}, {@code progress},
 * {@code content} (descriptor only — never raw bytes), {@code complete},
 * {@code error}, {@code resumed}.</p>
 *
 * <p>TEXT steps are <em>segments</em> of one logical output — coalescing
 * flushes on semantic boundaries, terminals, the size cap, and the flush
 * interval, so consumers must not treat fragment count as semantic.</p>
 *
 * @param runId   the run the step belongs to
 * @param seq     writer-assigned per-run sequence number
 * @param kind    step kind (see above)
 * @param payload JSON payload, versioned envelope {@code {"v":1,...}}
 * @param ts      epoch millis when the step was produced at the session boundary
 */
public record TapeStep(String runId, long seq, String kind, String payload, long ts) {

    public TapeStep {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
    }
}
