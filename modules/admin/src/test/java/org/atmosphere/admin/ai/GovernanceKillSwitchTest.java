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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceKillSwitchTest {

    private AtmosphereFramework framework;
    private Map<String, Object> properties;

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        properties = new HashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);
    }

    @Test
    void armAndDisarmRoundTrip() {
        var killSwitch = new KillSwitchPolicy();
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(killSwitch));
        var controller = new GovernanceController(framework);

        var armed = controller.armKillSwitch("incident-42", "sre-oncall");
        assertEquals(true, armed.get("armed"));
        assertEquals("incident-42", armed.get("reason"));
        assertEquals("sre-oncall", armed.get("operator"));
        assertNotNull(armed.get("armedAt"));

        var disarmed = controller.disarmKillSwitch();
        assertEquals(false, disarmed.get("armed"));
    }

    @Test
    void findsKillSwitchThroughTimedWrapper() {
        var killSwitch = new KillSwitchPolicy();
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(TimedPolicy.of(killSwitch)));

        new GovernanceController(framework).armKillSwitch("drill", "admin");
        assertEquals(true, killSwitch.isArmed());
    }

    @Test
    void armDefaultsOperatorToAdminWhenBlank() {
        var killSwitch = new KillSwitchPolicy();
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(killSwitch));
        var controller = new GovernanceController(framework);

        var armed = controller.armKillSwitch("reason", null);
        assertEquals("admin", armed.get("operator"));
    }

    @Test
    void armFailsWhenNoKillSwitchInstalled() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY,
                List.of(new DenyListPolicy("unrelated", "x")));
        var controller = new GovernanceController(framework);
        assertThrows(IllegalStateException.class,
                () -> controller.armKillSwitch("reason", "admin"));
        assertThrows(IllegalStateException.class, controller::disarmKillSwitch);
    }

    @Test
    void armFailsOnBlankReason() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(new KillSwitchPolicy()));
        var controller = new GovernanceController(framework);
        assertThrows(IllegalArgumentException.class,
                () -> controller.armKillSwitch("", "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.armKillSwitch(null, "admin"));
    }

    @Test
    void findsFirstKillSwitchWhenMultipleInstalled() {
        var first = new KillSwitchPolicy("k1", "code:test", "1");
        var second = new KillSwitchPolicy("k2", "code:test", "2");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(first, second));

        new GovernanceController(framework).armKillSwitch("drill", "admin");
        assertEquals(true, first.isArmed(),
                "first installed kill switch is the one armed");
        assertEquals(false, second.isArmed());
    }
}
