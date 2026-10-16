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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hands an application's {@code AiEnvironment} to the code
 * {@code QuarkusExtensionTest} runs after the application stopped or failed
 * to start ({@code setAfterUndeployListener}, {@code assertException}). That
 * code runs in the test's class loader, where {@code AiEnvironment} is another
 * class than the application's, so the instance travels through the JDK's
 * system properties and is read reflectively.
 */
final class AiEnvironmentHandoff {

    private AiEnvironmentHandoff() {
    }

    /** Called inside the application: keeps {@code environment} under {@code key}. */
    static void put(String key, Object environment) {
        System.getProperties().put(key, environment);
    }

    /** Called after the application: asserts the environment kept under {@code key} is closed. */
    static void assertClosed(String key) {
        var environment = System.getProperties().remove(key);
        assertNotNull(environment, "the application handed over its AI environment");
        assertTrue(isClosed(environment), "the application's AI environment " + environment + " is closed");
    }

    static boolean isClosed(Object environment) {
        try {
            return (Boolean) environment.getClass().getMethod("isClosed").invoke(environment);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read the state of " + environment, e);
        }
    }
}
