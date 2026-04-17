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
package org.atmosphere.ai.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide mandatory choke point for outbound LLM calls. Every runtime
 * bridge (Built-in, Spring AI, LangChain4j, ADK, Koog, Semantic Kernel,
 * Embabel) calls {@link #get()} before dispatching so rate limiting,
 * credential resolution, and unified tracing apply uniformly — without the
 * runtime bridges having to know how the gateway is configured.
 *
 * <h2>Default gateway</h2>
 *
 * Until {@link #install(AiGateway)} is called, a permissive default is
 * returned: {@link PerUserRateLimiter} sized at one million calls / hour
 * (effectively no-op), noop credential resolver, noop trace exporter.
 * Applications install a real gateway at startup via the Spring Boot
 * starter or Quarkus extension.
 *
 * <h2>Why a holder</h2>
 *
 * Wiring a dependency through every runtime bridge would require seven
 * constructor changes and break the contract tests. A process-wide holder
 * lets the gateway exist as an observable choke point without tying any
 * runtime bridge to a specific gateway instance; tests install their own
 * via {@link #install(AiGateway)} and restore the default in teardown.
 */
public final class AiGatewayHolder {

    private static final AtomicReference<AiGateway> HOLDER =
            new AtomicReference<>(defaultGateway());

    private AiGatewayHolder() {
        // static holder
    }

    /** Install the process-wide gateway. */
    public static void install(AiGateway gateway) {
        HOLDER.set(Objects.requireNonNull(gateway, "gateway"));
    }

    /** Restore the permissive default gateway. Primarily for tests. */
    public static void reset() {
        HOLDER.set(defaultGateway());
    }

    /** Fetch the current gateway. Never {@code null}. */
    public static AiGateway get() {
        return HOLDER.get();
    }

    private static AiGateway defaultGateway() {
        // One-million-calls-per-hour window is effectively unrestricted for
        // dev / test. Production deployments install a tighter limiter.
        var limiter = new PerUserRateLimiter(1_000_000, Duration.ofHours(1));
        return new AiGateway(
                limiter,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());
    }
}
