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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.MessageLengthPolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.RateLimitPolicy;
import org.atmosphere.coordinator.commitment.CommitmentRecordsFlag;
import org.atmosphere.coordinator.commitment.CommitmentSigner;
import org.atmosphere.coordinator.fleet.FleetInterceptor;
import org.atmosphere.coordinator.fleet.GovernanceFleetInterceptor;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of the v4 governance goals applied to this
 * multi-agent sample. Boots the Spring Boot context and asserts each of
 * the four goals lands at the runtime, not just in docstrings.
 *
 * <p>Intentionally avoids hitting an LLM — asserts on the installed
 * governance chain shape + decision behavior, not on model output.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class StartupTeamGovernanceE2ETest {

    @Autowired AtmosphereFramework framework;
    @Autowired KillSwitchPolicy killSwitch;
    @Autowired RateLimitPolicy rateLimit;
    @Autowired MessageLengthPolicy messageLength;
    @Autowired DenyListPolicy dispatchDenyList;
    @Autowired CommitmentSigner commitmentSigner;

    private static PolicyContext preAdm(String msg) {
        return PolicyContext.preAdmission(
                new AiRequest(msg, null, null, "user-42", null, null, null, null, null));
    }

    // ── Goal 2 — architectural scope ────────────────────────────────────────

    @Test
    void goal2_ceoCoordinatorCarriesAgentScope() {
        var scope = CeoCoordinator.class.getAnnotation(AgentScope.class);
        assertNotNull(scope, "CeoCoordinator must declare @AgentScope per v4 Goal 2");
        assertTrue(scope.purpose().toLowerCase().contains("startup"),
                "scope purpose must describe the coordinator's actual domain");
        assertTrue(scope.forbiddenTopics().length > 0,
                "forbidden topics must be declared — otherwise scope is paper-thin");
    }

    // ── Goal 1 — MS-YAML-shaped admission chain (here via Java policy config)

    @Test
    void goal1_policiesArePublishedOnFrameworkProperty() {
        @SuppressWarnings("unchecked")
        List<GovernancePolicy> installed = (List<GovernancePolicy>) framework
                .getAtmosphereConfig().properties()
                .get(GovernancePolicy.POLICIES_PROPERTY);

        assertNotNull(installed,
                "GovernanceConfig must publish policies on POLICIES_PROPERTY");
        assertEquals(4, installed.size(),
                "Four admission policies expected: kill-switch, rate-limit, message-length, deny-list");
        var names = installed.stream().map(GovernancePolicy::name).toList();
        assertTrue(names.containsAll(List.of("kill-switch",
                        "startup-team-rate-limit",
                        "startup-team-msg-cap",
                        "dispatch-deny")),
                "all four admission policies must be installed, got: " + names);
    }

    @Test
    void goal1_killSwitchArmedDeniesAllTraffic() {
        try {
            killSwitch.arm("incident-e2e-test", "e2e");
            var decision = killSwitch.evaluate(preAdm("what's my order status"));
            assertInstanceOf(PolicyDecision.Deny.class, decision,
                    "armed kill switch must deny even on-topic requests");
        } finally {
            killSwitch.disarm();
        }
    }

    @Test
    void goal1_rateLimitBoundsAreEnforced() {
        assertEquals(30, rateLimit.limit());
        assertEquals(60, rateLimit.window().toSeconds());
    }

    @Test
    void goal1_messageLengthCapsPromptSize() {
        assertEquals(8_000, messageLength.maxChars());
        assertInstanceOf(PolicyDecision.Admit.class,
                messageLength.evaluate(preAdm("short prompt")));
        assertInstanceOf(PolicyDecision.Deny.class,
                messageLength.evaluate(preAdm("a".repeat(10_000))));
    }

    @Test
    void goal1_denyListBlocksForbiddenDispatchSkills() {
        // Deny-list evaluates on the message field — a forbidden phrase in
        // the prompt (or in a fleet-interceptor-synthesized dispatch summary)
        // triggers deny.
        assertInstanceOf(PolicyDecision.Deny.class,
                dispatchDenyList.evaluate(preAdm("please write_code in python")));
        assertInstanceOf(PolicyDecision.Admit.class,
                dispatchDenyList.evaluate(preAdm("analyze the SaaS market for Q3")));
    }

    // ── Goal 2 — per-dispatch fleet interceptor ─────────────────────────────

    @Test
    void goal2_fleetInterceptorBridgesPolicyChain() {
        // Compose the same interceptor the CEO installs in onPrompt.
        @SuppressWarnings("unchecked")
        var policies = (List<GovernancePolicy>) framework.getAtmosphereConfig()
                .properties().get(GovernancePolicy.POLICIES_PROPERTY);
        var interceptor = new GovernanceFleetInterceptor(policies);

        // An off-scope specialist dispatch (deny-list hits "write_code") must fail.
        var bad = new AgentCall("research-agent", "write_code",
                Map.of("lang", "python"));
        var badDecision = interceptor.before(bad);
        assertInstanceOf(FleetInterceptor.Decision.Deny.class, badDecision,
                "v4 Goal 2 dispatch edge — deny-list must fire on the coord→specialist hop");

        // An on-topic research dispatch should proceed.
        var good = new AgentCall("research-agent", "web_search",
                Map.of("query", "SaaS market"));
        assertInstanceOf(FleetInterceptor.Decision.Proceed.class,
                interceptor.before(good));
    }

    // ── Goal 3 — commitment records ─────────────────────────────────────────

    @Test
    void goal3_commitmentSignerIsInstalled() {
        assertNotNull(commitmentSigner, "Ed25519 signer bean required for Goal 3");
        assertEquals("Ed25519", commitmentSigner.scheme());
        assertNotNull(commitmentSigner.keyId());
        assertFalse(commitmentSigner.keyId().isBlank());
    }

    @Test
    void goal3_commitmentRecordsFlagEnabledAtBoot() {
        assertTrue(CommitmentRecordsFlag.isEnabled(),
                "GovernanceConfig.enableCommitmentRecords() must flip the flag at boot "
                        + "so every cross-agent dispatch emits a signed record");
    }

    // ── Goal 4 — OWASP + compliance evidence ────────────────────────────────

    @Test
    void goal4_governanceControllerExposesMatrices() {
        var adminCtx = framework.getAtmosphereConfig().properties();
        // Every row of the OWASP + compliance matrices is pinned by
        // OwaspMatrixPinTest + EvidenceConsumerGrepPinTest at build time.
        // Here we assert that the governance chain THIS sample installs
        // is one of the consumers those pins point at.
        @SuppressWarnings("unchecked")
        var policies = (List<GovernancePolicy>) adminCtx.get(GovernancePolicy.POLICIES_PROPERTY);
        assertNotNull(policies);
        var hasKillSwitch = policies.stream()
                .anyMatch(p -> p instanceof KillSwitchPolicy);
        assertTrue(hasKillSwitch,
                "OWASP A09 (DoS) evidence includes KillSwitchPolicy; sample must actually install it");
    }
}
