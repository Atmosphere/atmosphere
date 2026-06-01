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
package org.atmosphere.interactions;

/**
 * Lifecycle state of an {@link Interaction}.
 *
 * <p>The terminal states ({@link #COMPLETED}, {@link #FAILED}, {@link #CANCELLED})
 * are the only ones an interaction can be left in once its run resolves; a
 * background run is persisted as {@link #RUNNING} the moment it is launched so a
 * {@code get} immediately after launch reports confirmed runtime state rather
 * than configuration intent (Correctness Invariant #5 — Runtime Truth).</p>
 */
public enum InteractionStatus {

    /** Persisted but not yet dispatched to the runtime. */
    CREATED,

    /** Dispatched to the runtime and producing steps. */
    RUNNING,

    /** The run finished successfully; {@code finalText} and {@code steps} are final. */
    COMPLETED,

    /** The run terminated with an error; {@code errorMessage} carries the cause. */
    FAILED,

    /** The run was cancelled before completing; captured steps are retained. */
    CANCELLED;

    /** Whether this is a terminal state (no further steps will be appended). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
