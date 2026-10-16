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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * An {@code @ApplicationScoped} {@link AiEnvironment} bean of the
 * application's own replaces the extension's: Quarkus gives the final class a
 * client proxy, the framework attaches the one instance behind it, and a
 * client a bean binds through the proxy is the framework's application's.
 */
public class AiEnvironmentApplicationScopedBeanTest {

    static final String CLIENT = "bound through the client proxy";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiEnvironmentApplicationScopedBeanTest.class,
                    ApplicationScopedProducer.class))
            .overrideConfigKey("quarkus.http.test-port", "0");

    /** Produces the application's environment in the application scope. */
    @ApplicationScoped
    public static class ApplicationScopedProducer {

        static final AtomicInteger CREATED = new AtomicInteger();

        @Produces
        @ApplicationScoped
        AiEnvironment environment() {
            CREATED.incrementAndGet();
            return AiEnvironment.create(Map.<String, String>of()::get);
        }
    }

    @Inject
    AiEnvironment environment;

    @Test
    public void theFrameworkAttachesTheInstanceBehindTheProxy() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        var attached = AiEnvironment.find(framework.getAtmosphereConfig()).orElse(null);
        assertNotNull(attached, "the framework attached the application's environment");

        assertEquals(1, ApplicationScopedProducer.CREATED.get(), "one environment for the application");
        assertEquals(attached.name(), environment.name(), "the injected proxy is the framework's environment");
        environment.bindClient(AiEnvironmentApplicationScopedBeanTest.class, String.class, CLIENT);
        assertEquals(CLIENT, attached.client(AiEnvironmentApplicationScopedBeanTest.class, String.class, null),
                "a client bound through the proxy is the framework's application's");
    }
}
