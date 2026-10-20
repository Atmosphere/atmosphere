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

import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiEndpointRegistrationTest} on the Vert.x container
 * ({@code quarkus.atmosphere.container=vertx}), which boots the framework
 * without the servlet: the application's {@code @AiEndpoint} registers there
 * too, with neither the LangChain4j bridge nor index configuration.
 */
public class AiEndpointRegistrationVertxTest {

    private static final String PATH = "/atmosphere/registration-probe";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiEndpointRegistrationVertxTest.class,
                    AiEndpointRegistrationTest.class, AiEndpointRegistrationTest.Probe.class))
            .overrideConfigKey("quarkus.atmosphere.container", "vertx")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Test
    public void anApplicationAiEndpointRegistersOnTheVertxContainer() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");

        var handlers = framework.getAtmosphereHandlers();
        assertTrue(handlers.keySet().stream().anyMatch(p -> p.startsWith(PATH)),
                "@AiEndpoint(path = \"" + PATH + "\") must register an AtmosphereHandler; registered paths: "
                        + handlers.keySet());
    }
}
