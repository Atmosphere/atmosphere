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
package org.atmosphere.ai.governance.memory;

import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceMemoryInstallerTest {

    @AfterEach
    void cleanup() {
        GovernanceDecisionLog.reset();
        GovernanceMemoryConfig.resetStore();
    }

    /** A framework whose property bag is a real, writable map for the one-shot marker. */
    private static AtmosphereFramework frameworkWithProps() {
        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        Map<String, Object> props = new ConcurrentHashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(props);
        return framework;
    }

    @Test
    void installsStoreAndSinkWhenEnabled() {
        var framework = frameworkWithProps();
        var cfg = new GovernanceMemoryConfig(true, null, 1.0, 0.0);

        var active = GovernanceMemoryInstaller.install(
                framework, cfg, new InMemoryLongTermMemory(), 50);

        assertTrue(active, "enabled -> activated");
        assertNotNull(GovernanceMemoryConfig.installedStore(), "provenance store published");
        assertTrue(GovernanceDecisionLog.installed().sinks().stream()
                        .anyMatch(s -> "governance-memory".equals(s.name())),
                "governance-memory sink registered");
    }

    @Test
    void noopWhenDisabled() {
        var framework = frameworkWithProps();
        var cfg = new GovernanceMemoryConfig(false, null, 1.0, 0.0);

        var active = GovernanceMemoryInstaller.install(
                framework, cfg, new InMemoryLongTermMemory(), 50);

        assertFalse(active, "disabled -> no activation");
        assertNull(GovernanceMemoryConfig.installedStore());
    }

    @Test
    void oneShotPerFramework() {
        var framework = frameworkWithProps();
        var cfg = new GovernanceMemoryConfig(true, null, 1.0, 0.0);

        assertTrue(GovernanceMemoryInstaller.install(
                framework, cfg, new InMemoryLongTermMemory(), 50), "first call installs");
        assertFalse(GovernanceMemoryInstaller.install(
                        framework, cfg, new InMemoryLongTermMemory(), 50),
                "second call on the same framework is a no-op (marker set) — no double sink");

        var governanceSinks = GovernanceDecisionLog.installed().sinks().stream()
                .filter(s -> "governance-memory".equals(s.name())).count();
        assertTrue(governanceSinks == 1, "exactly one governance-memory sink, got " + governanceSinks);
    }
}
