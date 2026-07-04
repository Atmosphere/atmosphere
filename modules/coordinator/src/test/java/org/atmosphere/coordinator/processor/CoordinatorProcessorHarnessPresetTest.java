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
package org.atmosphere.coordinator.processor;

import org.atmosphere.ai.CompactionConfig;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.LlmSummarizingCompaction;
import org.atmosphere.ai.SlidingWindowCompaction;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.preset.Harness;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.InterceptingAgentFleet;
import org.atmosphere.coordinator.test.StubAgentFleet;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the harness {@code @Coordinator} seams: delegate_task registration
 * gating on the DELEGATION feature, the governance wrap on the outbound
 * dispatch edge, and the compaction seam on the coordinator memory path.
 */
public class CoordinatorProcessorHarnessPresetTest {

    private CoordinatorProcessor processor;
    private AtmosphereFramework framework;
    private AtmosphereConfig cfg;
    private ConcurrentHashMap<String, Object> props;

    @BeforeEach
    public void setUp() {
        processor = new CoordinatorProcessor();
        framework = mock(AtmosphereFramework.class);
        cfg = mock(AtmosphereConfig.class);
        props = new ConcurrentHashMap<>();
        when(cfg.properties()).thenReturn(props);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
    }

    private HarnessPreset appWidePreset() {
        when(cfg.getInitParameter(HarnessPreset.ENABLED_KEY)).thenReturn("true");
        return HarnessPreset.install(framework);
    }

    // ---- delegate_task registration gating ----

    @Test
    public void delegateTaskRegisteredWhenDelegationOn() {
        var preset = appWidePreset();
        var registry = new DefaultToolRegistry();

        processor.registerPresetDelegation(registry, preset, true, "coord");

        assertTrue(registry.getTool("delegate_task").isPresent(),
                "the harness must register the built-in delegation tool");
        assertEquals("ACTIVE",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_DELEGATION),
                "runtime-state must report delegation as genuinely registered");
    }

    @Test
    public void delegateTaskAbsentWhenDelegationOff() {
        var preset = HarnessPreset.install(framework);
        var registry = new DefaultToolRegistry();

        processor.registerPresetDelegation(registry, preset, false, "coord");

        assertFalse(registry.getTool("delegate_task").isPresent(),
                "without the DELEGATION feature, delegation must stay opt-in via user @AiTool wrappers");
        assertEquals("INACTIVE(disabled)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_DELEGATION));
    }

    @Test
    public void userDeclaredDelegateTaskWinsOverThePresetTool() {
        // Regression: with DELEGATION now on by default, a coordinator that
        // hand-writes its own delegate_task wrapper collided with the preset's
        // registration — DefaultToolRegistry.register throws on a duplicate
        // name, which aborted the remaining deferred annotation processing.
        // The user tool is authoritative (same convention as the MEMORY path
        // deferring to a user-declared interceptor).
        var preset = appWidePreset();
        var registry = new DefaultToolRegistry();
        registry.register(new UserDelegateTaskWrapper());
        var userTool = registry.getTool("delegate_task").orElseThrow();

        processor.registerPresetDelegation(registry, preset, true, "coord");

        assertSame(userTool, registry.getTool("delegate_task").orElseThrow(),
                "the user-declared delegate_task must survive the preset registration");
        assertEquals("ACTIVE(user-tool)",
                preset.runtimeState().get(HarnessPreset.PRIMITIVE_DELEGATION),
                "runtime-state must report the user-provided delegation attach");
    }

    /** Hand-written delegation wrapper mirroring the documented pattern. */
    public static final class UserDelegateTaskWrapper {
        @org.atmosphere.ai.annotation.AiTool(name = "delegate_task",
                description = "user-declared delegation wrapper")
        public String delegate(String agent, String message) {
            return "user-tool: " + agent + " <- " + message;
        }
    }

    @Test
    public void coordinatorAnnotationDefaultsToTheFullHarness() throws Exception {
        // @Coordinator subsumes @Agent, so it shares the batteries-on default:
        // a bare annotation must resolve to the full feature set under an
        // UNSET app-wide flag (no config at all), keeping declared fleets'
        // delegation on without the old global switch.
        var defaults = (Harness[]) Coordinator.class.getMethod("harness").getDefaultValue();
        var preset = HarnessPreset.install(framework);

        var features = preset.featuresFor("/atmosphere/agent/coord", defaults, true);

        assertTrue(features.contains(Harness.DELEGATION),
                "a bare @Coordinator must keep fleet delegation on by default");
        assertTrue(features.contains(Harness.MEMORY),
                "a bare @Coordinator must carry the full batteries-on default");
    }

    // ---- outbound governance wrap ----

    @Test
    public void delegationOffLeavesFleetUnwrapped() {
        var fleet = StubAgentFleet.builder().agent("worker", "ok").build();

        assertSame(fleet, processor.applyPresetGovernance(fleet, false, framework, "coord"),
                "without the DELEGATION feature the fleet must pass through untouched");
    }

    @Test
    public void delegationWrapsFleetWithInstalledPolicies() {
        // The wrap must consume the SAME installed policy chain the inbound
        // pipeline resolves (GovernancePolicies.installed reads this bag).
        props.put(GovernancePolicy.POLICIES_PROPERTY, List.<GovernancePolicy>of(denyAll()));
        var fleet = StubAgentFleet.builder().agent("worker", "should never run").build();

        var governed = processor.applyPresetGovernance(fleet, true, framework, "coord");

        assertInstanceOf(InterceptingAgentFleet.class, governed,
                "the harness must return the interceptor-enforcing wrapper");
        var result = governed.agent("worker").call("default", Map.of("message", "hi"));
        assertFalse(result.success(), "an installed Deny policy must block the dispatch");
        assertTrue(result.text().contains("denied"), result.text());
        assertTrue(result.text().contains("blocked-by-test"),
                "the policy's reason must surface on the synthetic result: " + result.text());
    }

    @Test
    public void delegationGovernanceKeepsJournalVisible() {
        // The governance wrap composes AFTER the journal wrap; the governed
        // fleet must still expose the journal for @Prompt code and the
        // journal-format PostPromptHook (delegating journal(), not the NOOP
        // default).
        var journal = new org.atmosphere.coordinator.journal.InMemoryCoordinationJournal();
        AgentFleet fleet = StubAgentFleet.builder().agent("worker", "ok").build();
        fleet = new org.atmosphere.coordinator.journal.JournalingAgentFleet(
                fleet, journal, "coord");

        var governed = processor.applyPresetGovernance(fleet, true, framework, "coord");

        assertSame(journal, governed.journal(),
                "the governed fleet must delegate journal() to the journaled fleet");
    }

    // ---- compaction seam on the coordinator memory path ----

    @Test
    public void compactionSeamSelectsLlmSummarizing() {
        when(cfg.getInitParameter(CompactionConfig.STRATEGY_KEY)).thenReturn("summarizing");

        var memory = processor.resolveMemory(10, framework);

        assertInstanceOf(InMemoryConversationMemory.class, memory);
        assertInstanceOf(LlmSummarizingCompaction.class,
                ((InMemoryConversationMemory) memory).compactionStrategy());
    }

    @Test
    public void compactionDefaultsToSlidingWindow() {
        var memory = processor.resolveMemory(10, framework);

        assertInstanceOf(InMemoryConversationMemory.class, memory);
        assertInstanceOf(SlidingWindowCompaction.class,
                ((InMemoryConversationMemory) memory).compactionStrategy());
    }

    private static GovernancePolicy denyAll() {
        return new GovernancePolicy() {
            @Override
            public String name() {
                return "deny-all-preset-test";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public String source() {
                return "code:" + CoordinatorProcessorHarnessPresetTest.class.getName();
            }

            @Override
            public PolicyDecision evaluate(PolicyContext context) {
                return new PolicyDecision.Deny("blocked-by-test");
            }
        };
    }
}
