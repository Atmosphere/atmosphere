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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link AiRuntimeSelectionStartupTest} on the Vert.x container
 * ({@code quarkus.atmosphere.container=vertx}): an {@code atmosphere.ai.runtime}
 * that names no runtime on the classpath fails the startup there too.
 */
public class AiRuntimeSelectionVertxStartupTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AiRuntimeSelectionVertxStartupTest.class))
            .overrideConfigKey("quarkus.atmosphere.container", "vertx")
            .overrideConfigKey("atmosphere.ai.runtime", "no-such-runtime")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .assertException(t -> {
                Throwable root = t;
                var messages = new StringBuilder();
                while (root != null) {
                    messages.append(root).append('\n');
                    root = root.getCause();
                }
                assertTrue(messages.toString().contains(
                                "atmosphere.ai.runtime=no-such-runtime names no available AgentRuntime"),
                        messages.toString());
            });

    @Test
    public void startupFails() {
        fail("the application must not start");
    }
}
