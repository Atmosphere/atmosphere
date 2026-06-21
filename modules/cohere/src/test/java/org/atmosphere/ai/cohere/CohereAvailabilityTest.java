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
package org.atmosphere.ai.cohere;

import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CohereAgentRuntime#isAvailable()} reflects honest runtime
 * truth: available only when a Cohere credential is actually resolvable.
 *
 * <p>These cases drive the <em>production</em> {@code isAvailable()} (a plain
 * {@link CohereAgentRuntime}, not the contract test's always-available
 * subclass), so they prove the {@code COHERE_API_KEY} env-var tier is wired
 * through {@link org.atmosphere.ai.CredentialResolver}. The credential system
 * properties are mutated under a try/finally that restores their prior values,
 * and the {@link AiConfig} singleton is nulled and restored via reflection so
 * an ambient {@code settings.apiKey()} from another test cannot mark the
 * runtime available and mask the env-var tier under test. The OS environment is
 * never mutated.</p>
 */
class CohereAvailabilityTest {

    private static final String OVERRIDE_PROPERTY = "cohere.api.key";
    private static final String ENV_VAR_NAME = "COHERE_API_KEY";

    /**
     * With only the {@code COHERE_API_KEY} system property set (the sysprop
     * form of the env var, which {@code property()} reads before the OS
     * environment) and no {@code cohere.api.key} override and no AiConfig key,
     * the runtime must report available — proving the new env-var tier is
     * honored.
     */
    @Test
    void availableWhenOnlyEnvVarSet() {
        withCleanCredentials(() -> {
            System.setProperty(ENV_VAR_NAME, "cohere-from-env");
            assertTrue(new CohereAgentRuntime().isAvailable(),
                    "COHERE_API_KEY must make the Cohere runtime available");
        });
    }

    /**
     * With no provider override, no provider env var, and no AiConfig key, the
     * runtime must report unavailable.
     */
    @Test
    void unavailableWhenNothingSet() {
        withCleanCredentials(() ->
                assertFalse(new CohereAgentRuntime().isAvailable(),
                        "no Cohere credential anywhere must report unavailable"));
    }

    /**
     * Runs {@code body} with the {@code cohere.api.key} override and
     * {@code COHERE_API_KEY} system properties cleared and the
     * {@link AiConfig} singleton nulled; restores all three afterward so no
     * state leaks between tests.
     */
    private static void withCleanCredentials(Runnable body) {
        var savedOverride = System.getProperty(OVERRIDE_PROPERTY);
        var savedEnvVar = System.getProperty(ENV_VAR_NAME);
        System.clearProperty(OVERRIDE_PROPERTY);
        System.clearProperty(ENV_VAR_NAME);

        Object savedInstance;
        java.lang.reflect.Field instanceField;
        try {
            instanceField = AiConfig.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            savedInstance = instanceField.get(null);
            instanceField.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not access AiConfig singleton", e);
        }

        try {
            body.run();
        } finally {
            restore(OVERRIDE_PROPERTY, savedOverride);
            restore(ENV_VAR_NAME, savedEnvVar);
            try {
                instanceField.set(null, savedInstance);
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not restore AiConfig singleton", e);
            }
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
