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

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.SwappablePolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceReloadTest {

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

    private static PolicyContext ctx(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void reloadReplacesEnforcementOnMatchedSwap() {
        var swap = new SwappablePolicy("reloadable-rule",
                new DenyListPolicy("v1", "v1-phrase"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));
        var controller = new GovernanceController(framework);

        // Before reload: "v1-phrase" denies.
        assertInstanceOf(PolicyDecision.Deny.class, swap.evaluate(ctx("v1-phrase")));

        var yaml = """
                policies:
                  - name: v2
                    type: deny-list
                    version: "2"
                    config:
                      phrases: [v2-phrase]
                """;
        var result = controller.reloadSwappable("reloadable-rule", yaml);
        assertEquals("reloadable-rule", result.get("swapName"));

        // After reload: "v2-phrase" denies, "v1-phrase" admits.
        assertInstanceOf(PolicyDecision.Deny.class, swap.evaluate(ctx("v2-phrase")));
        assertInstanceOf(PolicyDecision.Admit.class, swap.evaluate(ctx("v1-phrase")));
    }

    @Test
    void reloadFindsSwappableThroughTimedWrapper() {
        var swap = new SwappablePolicy("wrapped",
                new DenyListPolicy("v1", "initial"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(TimedPolicy.of(swap)));

        var result = new GovernanceController(framework).reloadSwappable("wrapped", """
                policies:
                  - name: v2
                    type: deny-list
                    config:
                      phrases: [replaced]
                """);
        assertEquals("wrapped", result.get("swapName"));
    }

    @Test
    void reloadReportsBothIdentityOutAndIn() {
        var swap = new SwappablePolicy("r",
                new DenyListPolicy("outgoing-name", "x"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));

        var result = new GovernanceController(framework).reloadSwappable("r", """
                policies:
                  - name: incoming-name
                    type: deny-list
                    version: "99"
                    config:
                      phrases: [y]
                """);
        var outgoing = (Map<?, ?>) result.get("outgoing");
        var incoming = (Map<?, ?>) result.get("incoming");
        assertEquals("outgoing-name", outgoing.get("name"));
        assertEquals("incoming-name", incoming.get("name"));
        assertEquals("99", incoming.get("version"));
    }

    @Test
    void reloadFailsWhenSwapNameNotInstalled() {
        var swap = new SwappablePolicy("only-one",
                new DenyListPolicy("v1", "x"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));

        assertThrows(IllegalArgumentException.class,
                () -> new GovernanceController(framework).reloadSwappable("nope", """
                        policies:
                          - name: x
                            type: deny-list
                            config:
                              phrases: [y]
                        """));
    }

    @Test
    void reloadFailsOnMalformedYaml() {
        var swap = new SwappablePolicy("r", new DenyListPolicy("v1", "x"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));

        assertThrows(IllegalArgumentException.class,
                () -> new GovernanceController(framework).reloadSwappable("r", "[ not-valid-yaml"));
    }

    @Test
    void reloadFailsOnEmptyYaml() {
        var swap = new SwappablePolicy("r", new DenyListPolicy("v1", "x"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));

        assertThrows(IllegalArgumentException.class,
                () -> new GovernanceController(framework).reloadSwappable("r", "version: '1.0'"));
    }

    @Test
    void reloadFailsOnBlankInput() {
        var controller = new GovernanceController(framework);
        assertThrows(IllegalArgumentException.class,
                () -> controller.reloadSwappable("", "policies: []"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.reloadSwappable("x", ""));
    }
}
