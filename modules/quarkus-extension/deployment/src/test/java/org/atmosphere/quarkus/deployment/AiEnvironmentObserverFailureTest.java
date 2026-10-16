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
import jakarta.enterprise.event.Observes;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An observer of the application's {@link AiEnvironment} that fails (a native
 * client that cannot be built) fails the start, and the failed start closes
 * the environment: in dev mode the JVM outlives it, and the environment's
 * knobs would otherwise stay the host configuration the static API reads.
 */
public class AiEnvironmentObserverFailureTest {

    static final String FAILURE = "the native client could not be built";
    static final String HANDOFF = AiEnvironmentObserverFailureTest.class.getName() + ".environment";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    AiEnvironmentObserverFailureTest.class, FailingBinder.class, AiEnvironmentHandoff.class))
            .overrideConfigKey(AiConfig.TEMPERATURE_PROPERTY, "0.42")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .assertException(AiEnvironmentObserverFailureTest::assertEnvironmentClosed);

    static void assertEnvironmentClosed(Throwable t) {
        var messages = new StringBuilder();
        for (var cause = t; cause != null; cause = cause.getCause()) {
            messages.append(cause).append('\n');
        }
        assertTrue(messages.toString().contains(FAILURE), messages.toString());
        AiEnvironmentHandoff.assertClosed(HANDOFF);
    }

    /** Binds a client that cannot be built. */
    @ApplicationScoped
    public static class FailingBinder {

        void bind(@Observes AiEnvironment environment) {
            AiEnvironmentHandoff.put(HANDOFF, environment);
            throw new IllegalStateException(FAILURE);
        }
    }

    @Test
    public void startupFails() {
        fail("the application must not start");
    }
}
