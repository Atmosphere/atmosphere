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

/**
 * Lifecycle status of a {@link TapeRun}. A run starts {@link #OPEN} and
 * reaches exactly one terminal status — the status is <em>write-once</em>:
 * the first terminal wins, a later terminal signal is counted but never
 * flips the recorded status (Correctness Invariant #2, Terminal Path
 * Completeness).
 */
public enum TapeStatus {

    /** The run is in flight; steps are still being appended. */
    OPEN,

    /** The stream completed normally ({@code complete()} / {@code emit(Complete)}). */
    COMPLETED,

    /** The stream terminated with an error ({@code error(t)} / {@code emit(Error)}). */
    ERROR,

    /**
     * The client's transport disconnected while the run was still open.
     * Trailing steps may be produced-but-undelivered: the tape records
     * "as-produced at the session boundary, post-decorator", not delivered
     * truth — the leaf drops late writes after disconnect while the tape
     * (above the leaf) keeps recording until the cancel signal lands.
     */
    CANCELLED,

    /**
     * The idle sweep closed a run that saw no append for the configured
     * idle timeout and never reached an in-chain terminal. Write-once
     * still holds: ABANDONED only ever applies to runs with no prior
     * terminal.
     */
    ABANDONED;

    /** Whether this status is terminal (anything but {@link #OPEN}). */
    public boolean terminal() {
        return this != OPEN;
    }
}
