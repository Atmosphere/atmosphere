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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
