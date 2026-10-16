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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@link AiEnvironment} bean of the application's own that is
 * {@code @Dependent} (the CDI default) fails the start, before any instance of
 * it is created: the framework and each bean that injects it would otherwise
 * get an environment of their own, so a client a bean binds would never reach
 * the framework's endpoints, and the framework's environment would never be
 * closed.
 */
public class AiEnvironmentDependentBeanTest {

    static final String CREATED = AiEnvironmentDependentBeanTest.class.getName() + ".created";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiEnvironmentDependentBeanTest.class, DependentProducer.class))
            .overrideConfigKey(AiConfig.TEMPERATURE_PROPERTY, "0.42")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .assertException(AiEnvironmentDependentBeanTest::assertScopeRejected);

    static void assertScopeRejected(Throwable t) {
        var messages = new StringBuilder();
        for (var cause = t; cause != null; cause = cause.getCause()) {
            messages.append(cause).append('\n');
        }
        assertTrue(messages.toString().contains("is @Dependent: it must be @Singleton or @ApplicationScoped"), messages.toString());
        var created = (AtomicInteger) System.getProperties().remove(CREATED);
        assertEquals(0, created == null ? 0 : created.get(),
                "the start fails before the dependent producer creates an environment nobody would close");
    }

    /** Produces the application's environment in the default, {@code @Dependent}, scope. */
    @ApplicationScoped
    public static class DependentProducer {

        @Produces
        AiEnvironment environment() {
            ((AtomicInteger) System.getProperties().computeIfAbsent(CREATED, k -> new AtomicInteger()))
                    .incrementAndGet();
            return AiEnvironment.create(Map.<String, String>of()::get);
        }
    }

    @Test
    public void startupFails() {
        fail("the application must not start");
    }
}
