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
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application with the core Quarkus extension and {@code atmosphere-ai}, but
 * neither the LangChain4j bridge nor any {@code quarkus.index-dependency}
 * configuration, gets its {@code @AiEndpoint} registered. The endpoint class
 * is the application's own, so the build-time scan always finds it; what it
 * also needs is {@code AiEndpointProcessor}, the {@code @AtmosphereAnnotation}
 * handler that lives in the {@code atmosphere-ai} jar. Without that jar in the
 * Jandex index the handler is never registered and the endpoint is dropped
 * with nothing above a TRACE log.
 */
public class AiEndpointRegistrationTest {

    private static final String PATH = "/atmosphere/registration-probe";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiEndpointRegistrationTest.class, Probe.class))
            .overrideConfigKey("quarkus.http.test-port", "0");

    @AiEndpoint(path = PATH)
    @AgentScope(unrestricted = true, justification = "Test fixture: echoes any prompt.")
    public static class Probe {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.send(message);
            session.complete();
        }
    }

    @Test
    public void anApplicationAiEndpointRegistersWithoutIndexConfiguration() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");

        var handlers = framework.getAtmosphereHandlers();
        assertTrue(handlers.keySet().stream().anyMatch(p -> p.startsWith(PATH)),
                "@AiEndpoint(path = \"" + PATH + "\") must register an AtmosphereHandler; registered paths: "
                        + handlers.keySet());
    }
}
