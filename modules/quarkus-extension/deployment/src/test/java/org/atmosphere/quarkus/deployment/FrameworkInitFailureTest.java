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
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.quarkus.runtime.AtmosphereBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
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

    /**
     * A shutdown hook failing with an {@link Error} while the framework is
     * destroyed (a class that no longer links after a dev-mode reload) is
     * attached to the init failure, which stays the one reported.
     */
    @Test
    void anErrorDestroyingTheFrameworkKeepsTheInitFailure() {
        var framework = new AtmosphereFramework(false, false);
        var hookFailure = new NoClassDefFoundError("org/example/Gone");
        framework.getAtmosphereConfig().shutdownHook(() -> {
            throw hookFailure;
        });
        var cause = new ServletException("init failed");

        var failure = assertThrows(ServletException.class,
                () -> AtmosphereBootstrap.initFramework(framework, () -> {
                    throw cause;
                }));

        assertSame(cause, failure);
        assertSame(hookFailure, failure.getSuppressed()[0]);
    }

    /**
     * The runtime selection checked once the framework initialized is the
     * framework's own application's, whatever another application in the JVM
     * selected; the failed start closes that application's environment.
     */
    @Test
    void aFailedStartChecksAndClosesTheFrameworksOwnAiEnvironment() {
        var framework = new AtmosphereFramework(false, false);
        var environment = AiEnvironment.create(framework.getAtmosphereConfig(),
                Map.of(AiConfig.RUNTIME_PROPERTY, "no-such-runtime")::get);
        framework.getAtmosphereConfig().shutdownHook(environment::close);
        // Another application, started later, whose selection is valid.
        var other = AiEnvironment.create(Map.of(AiConfig.RUNTIME_PROPERTY, "built-in")::get);
        try {
            var failure = assertThrows(IllegalStateException.class,
                    () -> AtmosphereBootstrap.initFramework(framework, () -> { }));

            assertTrue(failure.getMessage().contains("no-such-runtime names no available AgentRuntime"),
                    failure.getMessage());
            assertTrue(environment.isClosed(), "the failed start closed the framework's AI environment");
            assertTrue(AiEnvironment.find(framework.getAtmosphereConfig()).isEmpty());
            assertFalse(other.isClosed(), "the other application's environment is left alone");
        } finally {
            other.close();
        }
    }

    /**
     * The environment the extension creates for the framework
     * ({@link AtmosphereBootstrap#bridgeAiKnobs}, as both container modes call
     * it inside {@code initFramework}) is closed by the failed start: a dev-mode
     * reload that fails does not leave it open beside the next application's.
     */
    @Test
    void aFailedStartClosesTheEnvironmentTheExtensionCreated() {
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "no-such-runtime");
        var framework = new AtmosphereFramework(false, false);
        var liveBefore = AiEnvironment.live().size();
        var created = new AtomicReference<AiEnvironment>();

        var failure = assertThrows(IllegalStateException.class,
                () -> AtmosphereBootstrap.initFramework(framework, () -> {
                    AtmosphereBootstrap.bridgeAiKnobs(framework);
                    created.set(AiEnvironment.find(framework.getAtmosphereConfig()).orElse(null));
                }));

        assertTrue(failure.getMessage().contains("no-such-runtime names no available AgentRuntime"),
                failure.getMessage());
        assertNotNull(created.get(), "the extension attached an AI environment to the framework");
        assertTrue(created.get().isClosed(), "the failed start closed the environment the extension created");
        assertTrue(AiEnvironment.find(framework.getAtmosphereConfig()).isEmpty());
        assertEquals(liveBefore, AiEnvironment.live().size(), "no AI environment is left open");
    }

    /** Destroying a framework that started closes the environment the extension created for it. */
    @Test
    void destroyingTheFrameworkClosesTheEnvironmentTheExtensionCreated() throws ServletException {
        var framework = new AtmosphereFramework(false, false);
        var created = new AtomicReference<AiEnvironment>();
        try {
            AtmosphereBootstrap.initFramework(framework, () -> {
                AtmosphereBootstrap.bridgeAiKnobs(framework);
                created.set(AiEnvironment.find(framework.getAtmosphereConfig()).orElse(null));
            });
            assertNotNull(created.get(), "the extension attached an AI environment to the framework");
            assertFalse(created.get().isClosed(), "a framework that started keeps its environment");
        } finally {
            framework.destroy();
        }

        assertTrue(created.get().isClosed(), "the framework's destroy closed its AI environment");
        assertFalse(AiEnvironment.live().contains(created.get()));
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
