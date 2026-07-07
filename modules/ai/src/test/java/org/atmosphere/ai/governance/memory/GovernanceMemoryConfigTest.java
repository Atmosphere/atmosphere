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

import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceMemoryConfigTest {

    @AfterEach
    void cleanup() {
        GovernanceMemoryConfig.resetStore();
    }

    @Test
    void defaultsAreOff() {
        var d = GovernanceMemoryConfig.defaults();
        assertFalse(d.enabled(), "durable recall is opt-in — off by default");
        assertNull(d.ttl(), "no expiry by default");
        assertEquals(1.0, d.confidence(), 0.001);
        assertEquals(0.0, d.minConfidence(), 0.001);
    }

    @Test
    void fromNullConfigYieldsDefaults() {
        assertEquals(GovernanceMemoryConfig.defaults(), GovernanceMemoryConfig.from(null));
    }

    @Test
    void fromInitParametersResolvesEnabledAndTtl() {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(GovernanceMemoryConfig.ENABLED_KEY, false)).thenReturn(true);
        when(cfg.getInitParameter(GovernanceMemoryConfig.TTL_SECONDS_KEY, 0)).thenReturn(3600);

        var resolved = GovernanceMemoryConfig.from(cfg);
        org.junit.jupiter.api.Assertions.assertTrue(resolved.enabled());
        assertEquals(Duration.ofSeconds(3600), resolved.ttl());
        assertEquals(1.0, resolved.confidence(), 0.001);
    }

    @Test
    void holderInstallsAndResets() {
        assertNull(GovernanceMemoryConfig.installedStore(), "default holder is empty (ephemeral)");
        var store = new GovernanceProvenanceMemory(new InMemoryLongTermMemory(), 0.0, Clock.systemUTC());
        GovernanceMemoryConfig.installStore(store);
        assertSame(store, GovernanceMemoryConfig.installedStore());
        GovernanceMemoryConfig.resetStore();
        assertNull(GovernanceMemoryConfig.installedStore());
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new GovernanceMemoryConfig(true, Duration.ZERO, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new GovernanceMemoryConfig(true, null, 1.5, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new GovernanceMemoryConfig(true, null, 1.0, -0.1));
    }
}
