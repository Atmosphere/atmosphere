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
 * Process-wide {@link TokenPricing} holder feeding the
 * {@code atmosphere.ai.cost} meter at the shared metrics seam
 * ({@code MetricsCapturingSession}) — the fix for the Tier-1 cost-observability
 * P1 where {@code AiMetrics.recordCost} had zero production callers and the
 * documented cost meter was permanently empty.
 *
 * <p>Defaults to {@link TokenPricing#ZERO}: with no rate sheet installed no
 * dollar figure is ever fabricated, and the cost meter stays empty — dollars
 * are advertised only when a real {@code TokenPricing} is configured
 * (Runtime Truth, Correctness Invariant #5). The Spring starters install the
 * application's {@code TokenPricing} bean here alongside the cost-ceiling
 * accountant; a bare deployment calls {@link #install} directly.</p>
 *
 * <p>Same holder idiom as {@link CostAccountantHolder}: the metrics decorator
 * is constructed on every dispatch across all entry modes, so a process-wide
 * holder keeps the wire concrete without threading a pricing dependency
 * through every runtime bridge.</p>
 */
public final class TokenPricingHolder {

    private static final AtomicReference<TokenPricing> HOLDER =
            new AtomicReference<>(TokenPricing.ZERO);

    private TokenPricingHolder() {
        // static holder
    }

    /** Install the process-wide pricing. */
    public static void install(TokenPricing pricing) {
        HOLDER.set(Objects.requireNonNull(pricing, "pricing"));
    }

    /** Restore the zero pricing. Primarily for tests. */
    public static void reset() {
        HOLDER.set(TokenPricing.ZERO);
    }

    /** Fetch the current pricing. Never {@code null}. */
    public static TokenPricing get() {
        return HOLDER.get();
    }
}
