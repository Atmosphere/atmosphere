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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.workspace.AgentDefinition;
import org.atmosphere.ai.workspace.WorkspaceExtensions;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery test for the five governance stages that previously had a
 * registered/config-buildable policy type but <b>no shipped install path</b>:
 * {@code message-length}, {@code rate-limit}, {@code concurrency-limit},
 * {@code time-window}, and {@code kill-switch}.
 *
 * <p>Each test drives the real shipped path — a {@code PERMISSIONS.md}
 * directive parsed by {@link WorkspaceExtensions#permissionPolicies} (the same
 * parser the {@code AgentProcessor} / {@code CoordinatorProcessor} run at
 * startup), installed via {@link WorkspaceExtensions#installPolicies}, then
 * enforced through {@link PolicyAdmissionGate#admit} — and asserts two
 * observable side effects: the gate returns {@link PolicyAdmissionGate.Result.Denied}
 * for a violating request, and the {@link GovernanceDecisionLog} records a
 * {@code deny} decision for the installed policy.</p>
 *
 * <p>Before this change only {@code allow-list} / {@code deny-list} /
 * {@code authorization} (3 of the 7 advertised stages) had an installer; these
 * tests pin the other 4 stages (5 policy types, since deny/allow share the
 * allow/deny stage) to their new install path so the "7 stages" claim can no
 * longer drift back to 3.</p>
 */
class GovernanceFiveStageInstallTest {

    private AtmosphereFramework framework;
    private Map<String, Object> properties;

    @BeforeEach
    void setUp() {
        framework = org.mockito.Mockito.mock(AtmosphereFramework.class);
        var config = org.mockito.Mockito.mock(AtmosphereConfig.class);
        properties = new HashMap<>();
        org.mockito.Mockito.when(framework.getAtmosphereConfig()).thenReturn(config);
        org.mockito.Mockito.when(config.properties()).thenReturn(properties);
        // Capture governance decisions so each test can assert the deny was
        // recorded, not merely returned.
        GovernanceDecisionLog.install(64);
    }

    @AfterEach
    void tearDown() {
        GovernanceDecisionLog.reset();
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** Minimal OpenClaw workspace carrying the given PERMISSIONS.md body. */
    private static AgentDefinition workspaceWithPermissions(Path root, String permissions)
            throws IOException {
        write(root, "AGENTS.md", "Operate carefully.");
        write(root, "SOUL.md", "You are Atlas, a meticulous assistant.");
        write(root, "PERMISSIONS.md", permissions);
        var def = WorkspaceExtensions.load(root.toString()).orElse(null);
        assertNotNull(def, "OpenClaw workspace should load");
        return def;
    }

    /** Run the shipped startup install path for the workspace's PERMISSIONS.md. */
    private void installFromPermissions(AgentDefinition def) {
        var policies = WorkspaceExtensions.permissionPolicies(def);
        assertTrue(!policies.isEmpty(),
                "PERMISSIONS.md must yield at least one installable policy");
        WorkspaceExtensions.installPolicies(framework, policies);
    }

    @SuppressWarnings("unchecked")
    private List<GovernancePolicy> installedPolicies() {
        return (List<GovernancePolicy>) properties.get(GovernancePolicy.POLICIES_PROPERTY);
    }

    private void assertDenyRecorded(String policyNamePrefix) {
        var recorded = GovernanceDecisionLog.installed().recent(32).stream()
                .anyMatch(e -> "deny".equals(e.decision())
                        && e.policyName().startsWith(policyNamePrefix));
        assertTrue(recorded,
                "a deny decision for '" + policyNamePrefix + "*' must be recorded in the decision log");
    }

    @Test
    void messageLengthStageInstallsAndDenies(@TempDir Path root) throws IOException {
        var def = workspaceWithPermissions(root, "message-length: 10");
        installFromPermissions(def);

        var result = PolicyAdmissionGate.admit(framework,
                new AiRequest("this message is definitely longer than ten characters"));

        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertTrue(denied.reason().contains("exceeds maximum"), denied.reason());
        assertDenyRecorded("workspace-message-length-");
    }

    @Test
    void rateLimitStageInstallsAndDenies(@TempDir Path root) throws IOException {
        var def = workspaceWithPermissions(root, "rate-limit: 1/60");
        installFromPermissions(def);

        // Limit is 1 per 60s for the same subject. First request admitted,
        // second (same anonymous subject, same window) denied.
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                PolicyAdmissionGate.admit(framework, new AiRequest("first")));
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("second"));

        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertTrue(denied.reason().contains("rate-limited"), denied.reason());
        assertDenyRecorded("workspace-rate-limit-");
    }

    @Test
    void concurrencyLimitStageInstallsAndDenies(@TempDir Path root) throws IOException {
        var def = workspaceWithPermissions(root, "concurrency-limit: 1");
        installFromPermissions(def);

        // First admission acquires the single slot; a second concurrent
        // admission (no post-response release between them) is denied.
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                PolicyAdmissionGate.admit(framework, new AiRequest("in-flight")));
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("second-concurrent"));

        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertTrue(denied.reason().contains("concurrency limit"), denied.reason());
        assertDenyRecorded("workspace-concurrency-limit-");
    }

    @Test
    void timeWindowStageInstallsAndDenies(@TempDir Path root) throws IOException {
        // Build a one-hour window that ends at the current UTC minute, so
        // "now" is always after the window's exclusive end (or the day is a
        // weekend, which the Mon-Fri default also denies) — deterministic, no
        // flakiness on the wall clock.
        var fmt = DateTimeFormatter.ofPattern("HH:mm");
        var end = LocalTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        var start = end.minusHours(1);
        var directive = "time-window: " + start.format(fmt) + "-" + end.format(fmt) + " UTC";

        var def = workspaceWithPermissions(root, directive);
        installFromPermissions(def);

        var result = PolicyAdmissionGate.admit(framework, new AiRequest("after hours"));

        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertTrue(denied.reason().contains("outside allowed window"), denied.reason());
        assertDenyRecorded("workspace-time-window-");
    }

    @Test
    void killSwitchStageInstallsDisarmedThenDeniesWhenArmed(@TempDir Path root) throws IOException {
        var def = workspaceWithPermissions(root, "kill-switch: enabled");
        installFromPermissions(def);

        // Installed DISARMED (safe default) — traffic flows until an operator
        // arms it. This mirrors the admin endpoint POST /governance/kill-switch/arm.
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                PolicyAdmissionGate.admit(framework, new AiRequest("before incident")));

        var killSwitch = installedPolicies().stream()
                .filter(p -> p instanceof KillSwitchPolicy)
                .map(p -> (KillSwitchPolicy) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("kill-switch policy must be installed"));
        killSwitch.arm("incident-2026-07", "admin");

        var result = PolicyAdmissionGate.admit(framework, new AiRequest("during incident"));

        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertTrue(denied.reason().contains("incident-2026-07"), denied.reason());
        assertDenyRecorded(KillSwitchPolicy.DEFAULT_NAME);
    }
}
