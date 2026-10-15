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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What closing an {@link AiEnvironment} does to the process default's
 * settings, the ones the static API returns on a thread bound to no
 * application while several are running.
 */
class AiEnvironmentCloseTest {

    private static final String URL = "http://127.0.0.1:1/v1";

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
    }

    private static AiEnvironment application() {
        return AiEnvironment.create(Map.<String, String>of()::get);
    }

    /**
     * Settings a static {@code AiConfig.configure} built while application A
     * was the only one running are both A's and the process default's. When A
     * closes, the process default takes the settings of the latest application
     * still running that has its own, so the static API stops returning a
     * closed application's settings and client, even with two applications
     * left (where the static API reads the process default).
     */
    @Test
    void closingTheApplicationWhoseSettingsTheProcessDefaultHoldsHandsThemToTheLatestSurvivor() {
        var a = application();
        var staticallyBuilt = AiConfig.configure("remote", "model-a", "k", URL);
        assertSame(staticallyBuilt, a.settings(), "the static configure targets the only application");
        assertSame(staticallyBuilt, AiEnvironment.processDefault().settings());
        var b = application();
        b.configure("remote", "model-b", "k", URL);
        var c = application();
        var settingsOfC = c.configure("remote", "model-c", "k", URL);

        a.close();

        assertSame(settingsOfC, AiEnvironment.processDefault().settings(),
                "the process default holds the latest surviving application's settings");
        assertNotSame(staticallyBuilt, AiConfig.get(),
                "the static API no longer returns the closed application's settings");
    }

    /** The process default keeps settings no application owns when one closes. */
    @Test
    void closingAnApplicationLeavesSettingsItDoesNotShareWithTheProcessDefault() {
        var processSettings = AiConfig.configure("remote", "process-model", "k", URL);
        var a = application();
        a.configure("remote", "model-a", "k", URL);
        var b = application();
        b.configure("remote", "model-b", "k", URL);

        a.close();

        assertSame(processSettings, AiEnvironment.processDefault().settings());
    }
}
