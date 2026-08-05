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
import java.util.ServiceConfigurationError;
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
 * (Runtime Truth, Correctness Invariant #5). The host starters (Spring Boot,
 * Quarkus) install the application's {@code TokenPricing} bean here alongside
 * the cost-ceiling accountant; a bare deployment calls {@link #install}
 * directly.</p>
 *
 * <p>Same holder idiom as {@link CostAccountantHolder}: the metrics decorator
 * is constructed on every dispatch across all entry modes, so a process-wide
 * holder keeps the wire concrete without threading a pricing dependency
 * through every runtime bridge.</p>
 */
public final class TokenPricingHolder {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(TokenPricingHolder.class);

    private static final AtomicReference<TokenPricing> HOLDER =
            new AtomicReference<>(TokenPricing.ZERO);

    private TokenPricingHolder() {
        // static holder
    }

    /** Set once discovery has run, so a classpath scan happens at most once. */
    private static final java.util.concurrent.atomic.AtomicBoolean DISCOVERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Install the process-wide pricing. An explicit install always wins over discovery. */
    public static void install(TokenPricing pricing) {
        HOLDER.set(Objects.requireNonNull(pricing, "pricing"));
        DISCOVERED.set(true);
    }

    /** Restore the zero pricing and re-arm discovery. Primarily for tests. */
    public static void reset() {
        HOLDER.set(TokenPricing.ZERO);
        DISCOVERED.set(false);
    }

    /**
     * Fetch the current pricing, resolving a {@link TokenPricing} provider from
     * the classpath on first use. Never {@code null}.
     *
     * <p>Resolution order: an explicit {@link #install} wins; otherwise the
     * highest-priority available {@link java.util.ServiceLoader} provider;
     * otherwise {@link TokenPricing#ZERO}. Discovery runs at most once, and a
     * provider that throws while loading is skipped rather than failing
     * dispatch — pricing is an observability concern and must never take the
     * request path down (Correctness Invariant #2).</p>
     *
     * <p>Falling back to {@code ZERO} is deliberate: with no rate sheet on the
     * classpath, cost reads as zero everywhere, which makes an unconfigured
     * cost ceiling visibly inert instead of quietly enforcing against wrong
     * numbers.</p>
     */
    public static TokenPricing get() {
        if (DISCOVERED.compareAndSet(false, true)) {
            discover().ifPresent(HOLDER::set);
        }
        return HOLDER.get();
    }

    /** Highest-priority available provider on the classpath, if any. */
    static java.util.Optional<TokenPricing> discover() {
        try {
            return java.util.ServiceLoader.load(TokenPricing.class).stream()
                    .map(provider -> {
                        try {
                            return provider.get();
                        } catch (RuntimeException | ServiceConfigurationError e) {
                            logger.warn("TokenPricing provider {} failed to load — skipping: {}",
                                    provider.type().getName(), e.toString());
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .filter(TokenPricing::isAvailable)
                    .max(java.util.Comparator.comparingInt(TokenPricing::priority)
                            .thenComparing(TokenPricing::name))
                    .map(found -> {
                        logger.info("TokenPricing resolved from the classpath: {} (priority {})",
                                found.name(), found.priority());
                        return found;
                    });
        } catch (RuntimeException | ServiceConfigurationError e) {
            logger.warn("TokenPricing discovery failed — cost stays zero: {}", e.toString());
            return java.util.Optional.empty();
        }
    }
}
