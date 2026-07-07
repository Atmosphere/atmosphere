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
package org.atmosphere.ai.governance.memory;

import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.memory.LongTermMemories;
import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * One-shot, framework-agnostic wiring for the durable governance-feedback path. Resolves the
 * {@link GovernanceMemoryConfig} for a framework and, when enabled, publishes a
 * {@link GovernanceProvenanceMemory} store (read by {@code GovernanceFeedbackInterceptor}) and
 * registers a {@link GovernanceMemorySink} on the {@link GovernanceDecisionLog}.
 *
 * <p>Shared by every runtime so the opt-in behaves identically (Correctness Invariant #7):
 * the Spring Boot auto-configuration calls {@link #install(AtmosphereFramework,
 * GovernanceMemoryConfig, LongTermMemory, int)} at context time; the framework-agnostic
 * {@code AiEndpointProcessor} calls {@link #install(AtmosphereFramework)} on the first
 * {@code @AiEndpoint} processed (Quarkus, bare-JVM). A per-framework one-shot marker in the
 * property bag makes whichever runs first win — the other is a no-op, so the sink is never
 * double-registered.</p>
 *
 * <p>Lifecycle stays with the caller (Correctness Invariant #1): the sink never closes the
 * store it was handed.</p>
 */
public final class GovernanceMemoryInstaller {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceMemoryInstaller.class);

    /** One-shot marker key in the framework property bag. */
    public static final String BRIDGED_MARKER = "org.atmosphere.ai.governance.memory.bridged";

    private GovernanceMemoryInstaller() {
    }

    /**
     * Framework-agnostic entry point: resolve the config from framework init-parameters and the
     * store from {@link LongTermMemories#resolve}, then install. Used by {@code AiEndpointProcessor}.
     *
     * @return {@code true} if this call activated durable recall
     */
    public static boolean install(AtmosphereFramework framework) {
        if (framework == null) {
            return false;
        }
        var cfg = GovernanceMemoryConfig.from(framework.getAtmosphereConfig());
        return install(framework, cfg, LongTermMemories.resolve(framework),
                GovernanceDecisionLog.DEFAULT_CAPACITY);
    }

    /**
     * Install with an explicit config and store. One-shot per framework: the first caller marks
     * the framework and (when enabled) wires the store + sink; later callers no-op.
     *
     * @param framework          the framework whose property bag carries the one-shot marker
     * @param cfg                the resolved durable-memory config
     * @param store              the store to persist into / recall from (never {@code null})
     * @param decisionLogCapacity capacity to install the decision log with if nothing else has
     * @return {@code true} if this call activated durable recall
     */
    public static boolean install(AtmosphereFramework framework, GovernanceMemoryConfig cfg,
                                  LongTermMemory store, int decisionLogCapacity) {
        if (framework == null || cfg == null || store == null) {
            return false;
        }
        var props = framework.getAtmosphereConfig() != null
                ? framework.getAtmosphereConfig().properties() : null;
        if (props == null || props.putIfAbsent(BRIDGED_MARKER, Boolean.TRUE) != null) {
            return false;                            // another caller already handled this framework
        }
        if (!cfg.enabled()) {
            return false;                            // opt-in off — ephemeral loop only
        }
        // Make the sink's fan-out fire even without atmosphere-admin: install the decision log
        // if nothing else has (install only over the NOOP, so an operator's own log survives).
        if (decisionLogCapacity > 0 && GovernanceDecisionLog.installed().capacity() == 0) {
            GovernanceDecisionLog.install(decisionLogCapacity);
        }
        var clock = Clock.systemUTC();
        GovernanceMemoryConfig.installStore(
                new GovernanceProvenanceMemory(store, cfg.minConfidence(), clock));
        Duration ttl = cfg.ttl();
        GovernanceDecisionLog.installed().addSink(
                new GovernanceMemorySink(store, ttl, cfg.confidence(), clock));
        logger.info("Durable governance recall enabled: ttl={}, confidence={}, minConfidence={} "
                        + "(store: {})", ttl == null ? "none" : ttl, cfg.confidence(),
                cfg.minConfidence(), store.getClass().getName());
        return true;
    }
}
