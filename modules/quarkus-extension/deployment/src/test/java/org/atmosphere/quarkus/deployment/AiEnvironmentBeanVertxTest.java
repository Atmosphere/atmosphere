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

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link AiEnvironmentBeanTest} on the Vert.x container
 * ({@code quarkus.atmosphere.container=vertx}), which boots the framework
 * without the servlet.
 */
public class AiEnvironmentBeanVertxTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiEnvironmentBeanVertxTest.class,
                    AiEnvironmentBeanTest.Binder.class, AiEnvironmentBeanTest.StartupUser.class,
                    AiEnvironmentHandoff.class))
            .overrideConfigKey(AiConfig.TEMPERATURE_PROPERTY, AiEnvironmentBeanTest.TEMPERATURE)
            .overrideConfigKey("quarkus.atmosphere.container", "vertx")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .setAfterUndeployListener(() -> AiEnvironmentHandoff.assertClosed(AiEnvironmentBeanTest.Binder.HANDOFF));

    @Inject
    AiEnvironment environment;

    @Inject
    AiEnvironmentBeanTest.Binder binder;

    @Inject
    AiEnvironmentBeanTest.StartupUser startupUser;

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
        assertEquals(AiEnvironmentBeanTest.Binder.CLIENT,
                attached.client(AiEnvironmentBeanTest.Binder.class, String.class, null));
        assertEquals(AiEnvironmentBeanTest.TEMPERATURE, attached.values().get(AiConfig.TEMPERATURE_PROPERTY));
    }
}
