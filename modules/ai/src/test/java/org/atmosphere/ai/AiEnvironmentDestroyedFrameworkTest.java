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
package org.atmosphere.ai;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiEnvironment#of(org.atmosphere.cpr.AtmosphereConfig)} on a framework
 * that was destroyed: its destroy already ran the shutdown hooks that close the
 * environments it owns, so an environment created for it then would stay open
 * for good and, next to the one application still running, would move the
 * static API ({@link AiEnvironment#current()}) off that application.
 */
class AiEnvironmentDestroyedFrameworkTest {

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
    }

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
    }

    @Test
    void aDestroyedFrameworkGetsTheProcessDefaultAndOpensNothing() {
        var running = AiEnvironment.create(key -> null);
        assertSame(running, AiEnvironment.current(), "one application open: the static API reads it");
        var destroyed = new AtmosphereFramework();
        destroyed.destroy();
        var before = AiEnvironment.live();

        var environment = AiEnvironment.of(destroyed.getAtmosphereConfig());

        assertSame(AiEnvironment.processDefault(), environment,
                "a destroyed framework gets the process default, never another application's environment");
        assertEquals(before, AiEnvironment.live(), "no environment is opened for a destroyed framework");
        assertTrue(AiEnvironment.find(destroyed.getAtmosphereConfig()).isEmpty());
        assertSame(running, AiEnvironment.current(),
                "the running application stays the one the static API reads");
    }

    @Test
    void theEnvironmentAFrameworkCreatedClosesOnDestroyAndIsNotReopened() {
        var framework = new AtmosphereFramework();
        var created = AiEnvironment.of(framework.getAtmosphereConfig());
        assertNotSame(AiEnvironment.processDefault(), created);

        framework.destroy();

        assertTrue(created.isClosed(), "the framework's destroy closes the environment it created");
        assertSame(AiEnvironment.processDefault(), AiEnvironment.of(framework.getAtmosphereConfig()));
        assertTrue(AiEnvironment.live().isEmpty(), "nothing stays open, saw " + AiEnvironment.live());
    }

    @Test
    void anEnvironmentWhoseShutdownHookCameTooLateIsClosedAtOnce() {
        var running = AiEnvironment.create(key -> null);
        var framework = new DestroyedRightAfterTheFirstCheck();
        var before = AiEnvironment.live();
        framework.armed.set(true);

        var environment = AiEnvironment.of(framework.getAtmosphereConfig());

        assertFalse(framework.armed.get(), "the destroy ran between the first check and the hook registration");
        assertSame(AiEnvironment.processDefault(), environment,
                "an environment created while the framework is destroyed is not handed out");
        assertEquals(before, AiEnvironment.live(),
                "the environment whose hook the destroy never ran is closed, not left open for good");
        assertTrue(AiEnvironment.find(framework.getAtmosphereConfig()).isEmpty());
        assertSame(running, AiEnvironment.current(),
                "the running application stays the one the static API reads");
    }

    /**
     * Reproduces, without timing, a destroy that runs on another thread while
     * {@link AiEnvironment#of(org.atmosphere.cpr.AtmosphereConfig)} is between
     * its first {@link #isDestroyed()} check and its shutdown hook
     * registration: the first armed check answers {@code false}, then the
     * framework's destroy runs its hooks and clears them before that
     * registration.
     */
    private static final class DestroyedRightAfterTheFirstCheck extends AtmosphereFramework {
        final AtomicBoolean armed = new AtomicBoolean();

        @Override
        public boolean isDestroyed() {
            if (armed.compareAndSet(true, false)) {
                var seen = super.isDestroyed();
                destroy();
                return seen;
            }
            return super.isDestroyed();
        }
    }
}
