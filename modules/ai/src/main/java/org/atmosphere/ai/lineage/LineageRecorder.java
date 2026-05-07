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
package org.atmosphere.ai.lineage;

/**
 * SPI for persisting {@link LineageEntry} records. Every {@code @Prompt}
 * invocation produces exactly one entry, emitted by
 * {@link LineageCapturingSession} on its terminal callback ({@code complete()}
 * or {@code error()}).
 *
 * <p>Ships with a {@link #NOOP} default and an
 * {@link InMemoryLineageRecorder} for development / tests. Production wires
 * a recorder that fans out to a persistent sink (Kafka, Postgres, S3) — same
 * pattern as {@code GovernanceDecisionLog} sinks.</p>
 *
 * <p>Recorders MUST be safe to invoke from any thread (the @Prompt VT, the
 * disconnect cleanup thread, etc.) and MUST NOT throw — implementations
 * should swallow infrastructure errors and log at WARN; throwing would block
 * the prompt from completing.</p>
 */
public interface LineageRecorder {

    /** No-op recorder. Default; replaced via {@link LineageRecorderHolder#install}. */
    LineageRecorder NOOP = entry -> { };

    /**
     * Record a single lineage entry. Called once per {@code @Prompt}
     * invocation on its terminal path.
     *
     * @param entry the lineage entry to persist; never null
     */
    void record(LineageEntry entry);
}
