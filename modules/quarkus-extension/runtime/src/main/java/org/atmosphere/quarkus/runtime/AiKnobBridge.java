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
package org.atmosphere.quarkus.runtime;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.cpr.AtmosphereFramework;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the application's {@link AiEnvironment} from the
 * {@code atmosphere.ai.*} knobs of the Quarkus configuration
 * ({@link AiConfig#knobProperties()}: generation parameters, planning,
 * filesystem, runtime, ...), so a value set in {@code application.properties}
 * is honoured as it is by the Spring Boot starters. The keys are the ones the
 * AI module documents, without a {@code quarkus.} prefix. A JVM system
 * property or the knob's {@code LLM_*} environment variable still wins
 * ({@link AiEnvironment#knob(String)}).
 *
 * <p>References {@code atmosphere-ai} types, an optional dependency: only
 * {@link AtmosphereBootstrap} calls it, once it found the module on the
 * classpath.</p>
 */
final class AiKnobBridge {

    private static final Logger logger = LoggerFactory.getLogger(AiKnobBridge.class);

    private AiKnobBridge() {
    }

    /**
     * Creates the environment, attaches it to {@code framework} before the
     * framework initializes, and closes it when the framework is destroyed
     * (Ownership, Correctness Invariant #1), so a dev-mode live reload, which
     * gets a new framework, gets a new environment.
     *
     * @return the environment
     */
    static AiEnvironment install(AtmosphereFramework framework) {
        var existing = AiEnvironment.find(framework.getAtmosphereConfig());
        if (existing.isPresent()) {
            return existing.get();
        }
        var config = ConfigProvider.getConfig();
        var environment = AiEnvironment.create(framework.getAtmosphereConfig(),
                key -> config.getOptionalValue(key, String.class).orElse(null));
        framework.getAtmosphereConfig().shutdownHook(environment::close);
        return environment;
    }

    /**
     * Resolves the {@code AgentRuntime} when {@code atmosphere.ai.runtime}
     * names one, so a name that matches no available runtime fails startup
     * (Correctness Invariant #5), as it does on the Spring Boot starters,
     * rather than the first request. Then names the generation knobs the LLM
     * settings in use do not apply, when they were built before the knobs were
     * installed ({@link AiConfig#reportKnobsNotInEffect()}).
     *
     * @throws IllegalStateException when the name matches no available runtime
     */
    static void verifyRuntimeSelection() {
        AiConfig.knob(AiConfig.RUNTIME_PROPERTY).ifPresent(requested ->
                logger.info("AgentRuntime '{}' selected by {}={}",
                        AgentRuntimeResolver.resolve().name(), AiConfig.RUNTIME_PROPERTY, requested));
        AiConfig.reportKnobsNotInEffect();
    }

    /**
     * As {@link #verifyRuntimeSelection()}, for the application of
     * {@code framework}: its environment's runtime knob and settings (the
     * static API's when no environment is attached to it).
     *
     * @throws IllegalStateException when the name matches no available runtime
     */
    static void verifyRuntimeSelection(AtmosphereFramework framework) {
        var environment = AiEnvironment.find(framework.getAtmosphereConfig()).orElse(null);
        if (environment == null) {
            verifyRuntimeSelection();
            return;
        }
        environment.knob(AiConfig.RUNTIME_PROPERTY).ifPresent(requested ->
                logger.info("AgentRuntime '{}' selected by {}={}",
                        environment.runtimes().resolve().name(), AiConfig.RUNTIME_PROPERTY, requested));
        environment.reportKnobsNotInEffect();
    }
}
