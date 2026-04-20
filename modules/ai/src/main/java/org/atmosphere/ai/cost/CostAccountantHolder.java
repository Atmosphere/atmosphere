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
package org.atmosphere.ai.cost;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide choke point for the {@link CostAccountant} the
 * {@code AiStreamingSession} decorator chain consults. Matches the
 * {@code AiGatewayHolder} pattern so production deployments install a
 * real accountant at startup while tests can install their own and
 * restore the no-op in teardown.
 *
 * <p>The holder exists because every runtime reports {@link TokenUsage}
 * through {@code StreamingSession.usage(...)} — a session-scoped call
 * site, not a request parameter. Wiring a dependency through every
 * runtime would require seven constructor changes and break the
 * contract tests. A process-wide holder keeps the observability →
 * enforcement wire concrete without coupling any runtime bridge to the
 * pricing layer.</p>
 */
public final class CostAccountantHolder {

    private static final AtomicReference<CostAccountant> HOLDER =
            new AtomicReference<>(CostAccountant.NOOP);

    private CostAccountantHolder() {
        // static holder
    }

    /** Install the process-wide accountant. */
    public static void install(CostAccountant accountant) {
        HOLDER.set(Objects.requireNonNull(accountant, "accountant"));
    }

    /** Restore the no-op accountant. Primarily for tests. */
    public static void reset() {
        HOLDER.set(CostAccountant.NOOP);
    }

    /** Fetch the current accountant. Never {@code null}. */
    public static CostAccountant get() {
        return HOLDER.get();
    }
}
