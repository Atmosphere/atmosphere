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

import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link CostGuardrailBridgeFailureTest} on the Vert.x container
 * ({@code quarkus.atmosphere.container=vertx}): a failing cost guardrail
 * bridge leaves no AI host configuration installed there either.
 */
public class CostGuardrailBridgeFailureVertxTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CostGuardrailBridgeFailureVertxTest.class,
                    CostGuardrailBridgeFailureTest.class,
                    CostGuardrailBridgeFailureTest.FailingGuardrailConfig.class,
                    CostGuardrailBridgeFailureTest.GuardrailFailure.class))
            .overrideConfigKey("quarkus.atmosphere.container", "vertx")
            .overrideConfigKey(AiConfig.TEMPERATURE_PROPERTY, "0.42")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .assertException(CostGuardrailBridgeFailureTest::assertHostConfigurationUninstalled);

    @Test
    public void startupFails() {
        fail("the application must not start");
    }
}
