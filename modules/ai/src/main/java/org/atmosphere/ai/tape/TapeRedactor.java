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

import java.util.Map;

/**
 * Capture-time redaction hook for the session tape — the fix for the Tier-1
 * plaintext-capture P1: the tape persists tool arguments, tool results, and
 * the full input prompt verbatim, so a secret passed as a tool argument or
 * PII in a conversation landed unmasked at rest. An installed redactor
 * transforms every step payload <em>before</em> it is serialized and written.
 *
 * <p><b>Opt-in.</b> The default {@link #NONE} is the identity — no behavior
 * change for existing deployments (an audit tape is intentionally a faithful
 * record; masking it is a policy decision the operator makes). Ship the
 * bundled {@link PiiTapeRedactor}, or implement this interface for
 * domain-specific masking, and install it via {@code TapeRecorder.Config}
 * (the Spring starters resolve a {@code TapeRedactor} bean; Quarkus a CDI
 * bean).</p>
 *
 * <p>Redaction is lossy and that is correct <em>for the tape</em>: the tape is
 * an observability record, never resumed or replayed into execution state.
 * (Checkpoint state, which IS resumed, uses reversible encryption instead —
 * see {@code CheckpointCipher} in {@code atmosphere-checkpoint}.)</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Must be thread-safe — invoked from producer threads and the writer.</li>
 *   <li>Should never throw; the recording seam is best-effort and falls back
 *       to the unredacted payload on failure rather than dropping the step
 *       (Correctness Invariant #4 — recording never breaks the live stream).
 *       A redactor that must fail closed can return a placeholder map.</li>
 *   <li>May return the input map unchanged (e.g. skipping non-sensitive
 *       {@code kind}s such as {@code progress} / {@code metadata} for
 *       performance).</li>
 * </ul>
 */
@FunctionalInterface
public interface TapeRedactor {

    /**
     * Redact one step payload before serialization.
     *
     * @param kind    the step kind ({@code input}, {@code text},
     *                {@code ai.tool.start}, {@code ai.tool.result},
     *                {@code metadata}, ...)
     * @param payload the payload map about to be serialized; implementations
     *                must treat it as read-only and return a new map when
     *                changing anything
     * @return the payload to persist — the input for "no change"
     */
    Map<String, Object> redact(String kind, Map<String, Object> payload);

    /** Identity redactor — the default: the tape records verbatim. */
    TapeRedactor NONE = (kind, payload) -> payload;
}
