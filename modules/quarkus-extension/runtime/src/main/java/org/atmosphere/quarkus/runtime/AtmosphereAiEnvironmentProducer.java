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

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.atmosphere.ai.AiEnvironment;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the application's {@link AiEnvironment} as a CDI bean, so a bean
 * can bind a native AI client for this application alone, for example
 * {@code LangChain4jAgentRuntime.setModel(environment, model)} from a bean
 * that observes or injects it, instead of through an adapter's deprecated
 * process-wide binder.
 *
 * <p>The environment is created from the {@code atmosphere.ai.*} knobs of the
 * Quarkus configuration the first time it is needed: when a bean injects it,
 * or at the latest when the Atmosphere framework initializes, which attaches
 * this same instance and then fires it as a synchronous CDI event
 * ({@link AiKnobBridge#install}). The framework initializes while the servlet
 * (or the Vert.x route) starts, before {@code @Startup} beans are created, so
 * a bean that must bind before the {@code @AiEndpoint}, {@code @Agent} and
 * {@code @Coordinator} classes register and resolve their runtime observes it:
 * {@code void bind(@Observes AiEnvironment environment)}. A binding made later
 * resets the runtimes the environment resolved so far, but an endpoint that
 * registered earlier keeps the runtime instance it resolved, with the client
 * that instance already built.</p>
 *
 * <p>It is a {@link Singleton}, one instance shared without a client proxy,
 * and a {@link DefaultBean}: a {@code @Singleton} or {@code @ApplicationScoped}
 * {@code AiEnvironment} bean of the application's own replaces it, and the
 * framework attaches that one. A replacement of any other scope, such as a
 * {@code @Dependent} producer (the CDI default), fails the start: the framework
 * and each bean that injects it would get an environment of their own.
 * This producer created the environment and {@link #dispose disposes} it when
 * the CDI container shuts down (Ownership, Correctness Invariant #1): when the
 * application stops, which a dev-mode live reload does, and when its start
 * fails. The framework's destroy leaves it alone.</p>
 *
 * <p>References {@code atmosphere-ai} types, an optional dependency: the
 * deployment processor registers it only when that module is on the
 * classpath.</p>
 */
@ApplicationScoped
public class AtmosphereAiEnvironmentProducer {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAiEnvironmentProducer.class);

    /**
     * The application's environment, created from the Quarkus configuration.
     *
     * @return a new open environment, owned by this producer
     */
    @Produces
    @Singleton
    @DefaultBean
    public AiEnvironment aiEnvironment() {
        var config = ConfigProvider.getConfig();
        var environment = AiEnvironment.create(key -> config.getOptionalValue(key, String.class).orElse(null));
        logger.debug("Created the application's AI environment {}", environment.name());
        return environment;
    }

    /**
     * Closes the environment {@link #aiEnvironment()} created. A CDI disposer
     * matches only the instances its own producer method made, so an
     * {@code AiEnvironment} bean of the application's own is never closed here.
     *
     * @param environment the environment this producer created
     */
    void dispose(@Disposes AiEnvironment environment) {
        environment.close();
    }
}
