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

import jakarta.servlet.ServletException;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.quarkus.runtime.AtmosphereBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both container modes initialize the framework through
 * {@link AtmosphereBootstrap#initFramework}: when the initialization or the AI
 * runtime-selection check after it fails, the framework is destroyed, so its
 * shutdown hooks run (they uninstall the AI host configuration) and its
 * threads stop. In dev mode the JVM outlives a failed start, so nothing else
 * would release them.
 */
class FrameworkInitFailureTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.RUNTIME_PROPERTY);
        AiConfig.resetHostConfig();
        AgentRuntimeResolver.reset();
    }

    @Test
    void aFailedRuntimeSelectionDestroysTheInitializedFramework() {
        var framework = new AtmosphereFramework(false, false);
        var installed = AiConfig.installHostConfig(Map.of(AiConfig.RUNTIME_PROPERTY, "no-such-runtime")::get);
        framework.getAtmosphereConfig().shutdownHook(installed::close);
        var initialized = new AtomicBoolean();

        var failure = assertThrows(IllegalStateException.class,
                () -> AtmosphereBootstrap.initFramework(framework, () -> initialized.set(true)));

        assertTrue(failure.getMessage().contains("no-such-runtime names no available AgentRuntime"),
                failure.getMessage());
        assertTrue(initialized.get(), "the selection is checked once the framework initialized");
        assertTrue(framework.isDestroyed(), "the initialized framework is destroyed");
        assertTrue(installed.values().containsKey(AiConfig.RUNTIME_PROPERTY));
        if (System.getenv(AiConfig.RUNTIME_ENV) == null) {
            assertTrue(AiConfig.knob(AiConfig.RUNTIME_PROPERTY).isEmpty(),
                    "the framework's shutdown hook uninstalled the host configuration");
        }
    }

    @Test
    void aFailedInitDestroysTheFramework() {
        var framework = new AtmosphereFramework(false, false);
        var hookRan = new AtomicBoolean();
        framework.getAtmosphereConfig().shutdownHook(() -> hookRan.set(true));
        var cause = new ServletException("init failed");

        var failure = assertThrows(ServletException.class,
                () -> AtmosphereBootstrap.initFramework(framework, () -> {
                    throw cause;
                }));

        assertSame(cause, failure);
        assertTrue(hookRan.get(), "the framework's shutdown hooks ran");
    }

    @Test
    void aSuccessfulInitKeepsTheFramework() throws ServletException {
        var framework = new AtmosphereFramework(false, false);
        var hookRan = new AtomicBoolean();
        framework.getAtmosphereConfig().shutdownHook(() -> hookRan.set(true));
        try {
            AtmosphereBootstrap.initFramework(framework, () -> { });
            assertFalse(hookRan.get(), "a framework that started is left running");
        } finally {
            framework.destroy();
        }
    }
}
