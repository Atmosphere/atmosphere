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
package org.atmosphere.ai.quarkus.langchain4j.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without an application {@link AiEnvironment} bean (the Atmosphere Quarkus
 * extension absent; emulated here by excluding the extension's producer of
 * it, since this module's tests have the extension on their class path), the
 * bridge binds the Quarkus LangChain4j model for the JVM through the
 * process-wide binder, as it always did.
 */
public class AtmosphereQuarkusLangChain4jBridgeWithoutEnvironmentTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereQuarkusLangChain4jBridgeWithoutEnvironmentTest.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "org.atmosphere.quarkus.runtime.AtmosphereAiEnvironmentProducer")
            .overrideConfigKey("quarkus.langchain4j.openai.api-key", "test-key")
            .overrideConfigKey("quarkus.langchain4j.openai.base-url", "http://localhost:9/v1/");

    @Inject
    Instance<AiEnvironment> environments;

    @Test
    public void theModelIsBoundForTheJvm() {
        assertTrue(environments.isUnsatisfied(), "no application AiEnvironment bean");
        assertTrue(AiEnvironment.processDefault().hasExplicitBinding(),
                "the bridge bound the model through the process-wide binder");
    }
}
