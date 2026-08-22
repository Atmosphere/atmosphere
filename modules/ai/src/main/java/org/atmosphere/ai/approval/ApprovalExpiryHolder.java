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
package org.atmosphere.ai.approval;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide {@link ApprovalExpiry} installation point, mirroring
 * {@code DurableRunSpineHolder}: the framework integration (Spring/Quarkus)
 * installs the durable backstop at startup when the crash-durable journal is
 * active; the default is no backstop, so a deployment that never opts in
 * pays nothing and keeps the pre-existing live-deadline-only behaviour.
 *
 * @since 4.0
 */
public final class ApprovalExpiryHolder {

    private static final AtomicReference<ApprovalExpiry> HOLDER = new AtomicReference<>();

    private ApprovalExpiryHolder() {
        // static holder
    }

    /** Install the process-wide expiry backstop (auto-config opt-in). */
    public static void install(ApprovalExpiry expiry) {
        HOLDER.set(Objects.requireNonNull(expiry, "expiry"));
    }

    /** Remove the backstop (shutdown / tests). */
    public static void reset() {
        HOLDER.set(null);
    }

    /** The current backstop, or {@code null} when none is installed. */
    public static ApprovalExpiry current() {
        return HOLDER.get();
    }
}
