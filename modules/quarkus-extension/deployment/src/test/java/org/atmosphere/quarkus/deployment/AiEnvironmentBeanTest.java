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
package org.atmosphere.quarkus.deployment;

import io.quarkus.runtime.Startup;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application's {@link AiEnvironment} is a CDI bean: the framework
 * attaches it, an observer of it binds a native client into it before the
 * framework initializes (so before any {@code @AiEndpoint} resolves its
 * runtime), and any bean can inject it. The client is then the application's
 * own rather than the JVM's. The CDI container closes the environment when the
 * application stops.
 */
public class AiEnvironmentBeanTest {

    static final String TEMPERATURE = "0.31";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiEnvironmentBeanTest.class, Binder.class,
                    StartupUser.class, AiEnvironmentHandoff.class))
            .overrideConfigKey(AiConfig.TEMPERATURE_PROPERTY, TEMPERATURE)
            .overrideConfigKey("quarkus.http.test-port", "0")
            .setAfterUndeployListener(() -> AiEnvironmentHandoff.assertClosed(Binder.HANDOFF));

    /** Binds a native client for this application, as {@code AtmosphereQuarkusLangChain4jBridge} does. */
    @ApplicationScoped
    public static class Binder {

        static final String HANDOFF = Binder.class.getName() + ".environment";
        static final String CLIENT = "the application's own client";

        private final AtomicInteger events = new AtomicInteger();
        private volatile AiEnvironment observed;
        private volatile boolean frameworkInitialized;

        void bind(@Observes AiEnvironment environment) {
            events.incrementAndGet();
            observed = environment;
            frameworkInitialized = LazyAtmosphereConfigurator.getFramework() != null;
            environment.bindClient(Binder.class, String.class, CLIENT);
            AiEnvironmentHandoff.put(HANDOFF, environment);
        }

        int events() {
            return events.get();
        }

        AiEnvironment observed() {
            return observed;
        }

        boolean frameworkInitialized() {
            return frameworkInitialized;
        }
    }

    /** A startup bean that injects the environment. */
    @ApplicationScoped
    @Startup
    public static class StartupUser {

        @Inject
        AiEnvironment environment;

        AiEnvironment environment() {
            return environment;
        }
    }

    @Inject
    AiEnvironment environment;

    @Inject
    Binder binder;

    @Inject
    StartupUser startupUser;

    @Test
    public void theFrameworkAttachesTheEnvironmentBeansInjectAndObserve() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        var attached = AiEnvironment.find(framework.getAtmosphereConfig()).orElse(null);

        assertNotNull(attached, "the extension attached the application's AI environment to its framework");
        assertSame(environment, attached, "the framework's environment is the CDI bean");
        assertSame(attached, startupUser.environment());
        assertSame(attached, binder.observed(), "the observer received the framework's environment");
        assertEquals(1, binder.events(), "the environment is fired once, when the framework attaches it");
        assertFalse(binder.frameworkInitialized(),
                "the observer runs before the framework initializes and its endpoints register");
        assertEquals(Binder.CLIENT, attached.client(Binder.class, String.class, null),
                "the client the observer bound is the framework's application's");
        assertEquals(TEMPERATURE, attached.values().get(AiConfig.TEMPERATURE_PROPERTY),
                "the bean is created from the Quarkus configuration's knobs");
        assertTrue(AiEnvironment.live().contains(attached));
        assertFalse(attached.isClosed());
    }
}
