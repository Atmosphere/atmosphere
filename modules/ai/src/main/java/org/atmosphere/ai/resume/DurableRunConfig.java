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

import java.time.Duration;
import java.util.Objects;

/**
 * The operator-facing knobs for the durable-execution spine, resolved once at
 * startup and held by the {@link DurableRunSpine}. Durable runs are
 * <strong>off by default</strong> ({@link #disabled()}): the spine installs no
 * scope, every journaled seam takes its byte-identical non-durable fast path,
 * and there is no behavioral or wire change until an operator explicitly opts
 * in (mirroring Correctness Invariant&nbsp;#6 — no insecure/surprising defaults).
 *
 * @param enabled         whether the spine drives runs durably at all
 * @param leaseTtl        how long a single-writer run lease is held before it is
 *                        eligible for takeover by a crash-recovery re-drive
 * @param retainOnSuccess keep a run's effect history after it completes
 *                        successfully (audit / inspection); when {@code false}
 *                        the history is pruned on success and only runs that
 *                        ended in failure are retained for resume
 * @since 4.0
 */
public record DurableRunConfig(boolean enabled, Duration leaseTtl, boolean retainOnSuccess) {

    public DurableRunConfig {
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive: " + leaseTtl);
        }
    }

    /** The default off posture: no durable scope is ever installed. */
    public static DurableRunConfig disabled() {
        return new DurableRunConfig(false, Duration.ofMinutes(5), false);
    }
}
