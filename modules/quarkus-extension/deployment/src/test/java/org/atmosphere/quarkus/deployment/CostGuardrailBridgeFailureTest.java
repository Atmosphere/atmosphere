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
import jakarta.inject.Singleton;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The cost guardrail is bridged after the {@code atmosphere.ai.*} knobs were
 * installed as the AI host configuration: when bridging it fails the startup,
 * the framework is destroyed, so its shutdown hook uninstalls that host
 * configuration instead of leaving it installed in a JVM that, in dev mode,
 * outlives the failed start.
 */
public class CostGuardrailBridgeFailureTest {

    static final String FAILURE = "the cost guardrail bean failed";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    CostGuardrailBridgeFailureTest.class, FailingGuardrailConfig.class, GuardrailFailure.class))
            .overrideConfigKey(AiConfig.TEMPERATURE_PROPERTY, "0.42")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .assertException(CostGuardrailBridgeFailureTest::assertHostConfigurationUninstalled);

    /**
     * The application's {@code AiConfig} is loaded by its own class loader,
     * not this one: the {@link GuardrailFailure} thrown inside the application
     * reads it when its message is asked for, after the failed start was
     * cleaned up.
     */
    static void assertHostConfigurationUninstalled(Throwable t) {
        var messages = new StringBuilder();
        for (var cause = t; cause != null; cause = cause.getCause()) {
            messages.append(cause).append('\n');
        }
        assertTrue(messages.toString().contains(FAILURE), messages.toString());
        if (System.getenv(AiConfig.TEMPERATURE_ENV) == null) {
            assertTrue(messages.toString().contains(FAILURE + "; " + AiConfig.TEMPERATURE_PROPERTY + " now unset"),
                    "the failed start left its AI host configuration installed:\n" + messages);
        }
    }

    /** Reports, when asked, the knob value the application reads now. */
    static final class GuardrailFailure extends IllegalStateException {

        GuardrailFailure() {
            super(FAILURE);
        }

        @Override
        public String getMessage() {
            return FAILURE + "; " + AiConfig.TEMPERATURE_PROPERTY + " now "
                    + AiConfig.knob(AiConfig.TEMPERATURE_PROPERTY).orElse("unset");
        }
    }

    /** A user guardrail bean whose creation fails while the framework bridges it. */
    @ApplicationScoped
    static class FailingGuardrailConfig {
        @Produces
        @Singleton
        CostCeilingGuardrail guardrail() {
            throw new GuardrailFailure();
        }
    }

    @Test
    public void startupFails() {
        fail("the application must not start");
    }
}
