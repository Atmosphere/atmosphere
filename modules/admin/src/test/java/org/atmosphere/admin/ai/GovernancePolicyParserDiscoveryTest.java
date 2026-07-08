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
import org.atmosphere.ai.governance.PolicyAdmissionGate;
import org.atmosphere.ai.governance.SwappablePolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Delivery test for the ServiceLoader-based {@code PolicyParser} discovery in
 * {@link GovernanceController#reloadSwappable(String, String, String)} — the
 * production consumer that makes "policies in AWS Cedar or OPA Rego via
 * adapters over the engines' own CLIs" reachable at runtime rather than a
 * dangling SPI. The {@code atmosphere-ai-policy-cedar} and
 * {@code atmosphere-ai-policy-rego} adapters are on the test classpath, so the
 * governance reload path actually resolves them by declared format, parses the
 * policy artifact into an enforceable {@link GovernancePolicy}, and swaps it
 * into the live chain.
 *
 * <p>Each engine assertion drives a request through the real admission path
 * ({@link PolicyAdmissionGate#admit}) and asserts the swapped-in adapter denies
 * — an observable side effect, not merely that a type exists. The Cedar / Rego
 * policies used here deny by construction: the {@code cedar} / {@code opa}
 * binaries are not required in CI, and the adapters fail closed (Correctness
 * Invariant #2) when the CLI is absent, so the admission decision is a
 * deterministic {@link PolicyAdmissionGate.Result.Denied} either way. That the
 * reload even succeeds proves the Cedar / Rego parser (not the YAML default)
 * handled the artifact — the YAML parser would throw on Cedar / Rego syntax.</p>
 */
class GovernancePolicyParserDiscoveryTest {

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

    private static AiRequest request() {
        return new AiRequest("please invoke the billing agent",
                null, null, "alice", null, "billing", null, null, null);
    }

    private SwappablePolicy installSwap(String name) {
        var swap = new SwappablePolicy(name, new DenyListPolicy("initial", "unused-phrase"));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));
        return swap;
    }

    @Test
    void cedarPolicyIsDiscoveredParsedAndEnforcedAsDeny() {
        installSwap("access-rule");
        var controller = new GovernanceController(framework);

        // A Cedar module with no `permit` — the default-deny outcome. `@id`
        // gives the adapter a stable policy name so we can prove the Cedar
        // parser (not YAML) produced the policy.
        var cedar = """
                @id("cedar-forbid-all")
                forbid(principal, action, resource);
                """;

        var result = controller.reloadSwappable("access-rule", cedar, "cedar");
        assertEquals("cedar", result.get("format"), "reload must route through the Cedar parser");
        var incoming = (Map<?, ?>) result.get("incoming");
        assertEquals("cedar-forbid-all", incoming.get("name"),
                "Cedar @id must drive the incoming policy name");
        assertTrue(String.valueOf(incoming.get("source")).startsWith("cedar:"),
                "incoming source must carry the cedar: scheme");

        // Drive a request through the real admission path: the swapped-in
        // Cedar policy must deny.
        var decision = PolicyAdmissionGate.admit(framework, request());
        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, decision,
                "Cedar policy must deny through the admission gate");
        assertTrue(denied.reason().toLowerCase(Locale.ROOT).contains("cedar"),
                "deny must come from the Cedar adapter, got: " + denied.reason());
    }

    @Test
    void regoPolicyIsDiscoveredParsedAndEnforcedAsDeny() {
        installSwap("rego-rule");
        var controller = new GovernanceController(framework);

        // A Rego module whose `allow` never becomes true — default deny.
        var rego = """
                package atmosphere.governance.delivery

                default allow = false
                """;

        var result = controller.reloadSwappable("rego-rule", rego, "rego");
        assertEquals("rego", result.get("format"), "reload must route through the Rego parser");
        var incoming = (Map<?, ?>) result.get("incoming");
        assertEquals("atmosphere.governance.delivery", incoming.get("name"),
                "Rego package declaration must drive the incoming policy name");
        assertTrue(String.valueOf(incoming.get("source")).startsWith("rego:"),
                "incoming source must carry the rego: scheme");

        var decision = PolicyAdmissionGate.admit(framework, request());
        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, decision,
                "Rego policy must deny through the admission gate");
    }

    @Test
    void unknownFormatFailsClosed() {
        installSwap("r");
        var controller = new GovernanceController(framework);
        // No PolicyParser advertises "sentinel" — must reject, never silently
        // fall back to YAML (Correctness Invariant #6).
        var ex = assertThrows(IllegalArgumentException.class,
                () -> controller.reloadSwappable("r", "whatever", "sentinel"));
        assertTrue(ex.getMessage().contains("sentinel"),
                "error must name the unresolved format, got: " + ex.getMessage());
    }

    @Test
    void yamlRemainsTheDefaultFormat() {
        var swap = installSwap("y");
        var controller = new GovernanceController(framework);
        var yaml = """
                policies:
                  - name: yaml-deny
                    type: deny-list
                    config:
                      phrases: [forbidden]
                """;

        // Two-arg overload and explicit "yaml" both resolve the YAML parser.
        var result = controller.reloadSwappable("y", yaml);
        assertEquals("yaml", result.get("format"));

        var decision = PolicyAdmissionGate.admit(framework,
                new AiRequest("this is forbidden content", null, null, null, null, null, null, null, null));
        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, decision,
                "YAML deny-list must still enforce after a default-format reload");
        // The swap slot keeps admitting a non-matching message.
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                PolicyAdmissionGate.admit(framework,
                        new AiRequest("hello", null, null, null, null, null, null, null, null)),
                "non-matching message must be admitted by the YAML deny-list");
        // The SwappablePolicy identity label stays pinned across the reload.
        assertEquals("y", swap.name(), "swap identity label stays pinned across reload");
    }
}
