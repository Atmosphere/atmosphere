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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide accessor for the live {@link DurableRunSpine}, same pattern as
 * {@link RunRegistryHolder}. The endpoint handler reaches the one spine instance
 * through this holder to begin and complete each run; auto-configuration
 * (Spring/Quarkus) installs a spine backed by the resolved {@link EffectJournal}
 * at startup, while the default is {@link DurableRunSpine#disabled()} so a plain
 * deployment that never opts in pays nothing.
 *
 * <p>It is the existence of this holder — a real producer on the run path — that
 * turns the durable-execution journal from a published SPI into a primitive
 * reached from an actual HTTP request.</p>
 *
 * @since 4.0
 */
public final class DurableRunSpineHolder {

    private static final AtomicReference<DurableRunSpine> HOLDER =
            new AtomicReference<>(DurableRunSpine.disabled());

    private DurableRunSpineHolder() {
        // static holder
    }

    /** Install the process-wide spine (auto-config opt-in). */
    public static void install(DurableRunSpine spine) {
        HOLDER.set(Objects.requireNonNull(spine, "spine"));
    }

    /** Restore the default disabled spine. Primarily for tests. */
    public static void reset() {
        HOLDER.set(DurableRunSpine.disabled());
    }

    /** Fetch the current spine. Never {@code null}. */
    public static DurableRunSpine get() {
        return HOLDER.get();
    }
}
