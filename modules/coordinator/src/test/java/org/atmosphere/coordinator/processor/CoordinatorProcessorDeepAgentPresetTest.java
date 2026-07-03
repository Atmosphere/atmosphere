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
import org.atmosphere.ai.preset.DeepAgentPreset;
import org.atmosphere.ai.tool.DefaultToolRegistry;
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
 * Pins the deep-agent preset's {@code @Coordinator} seams: delegate_task
 * registration gating, the governance wrap on the outbound dispatch edge, and
 * the compaction seam on the coordinator memory path.
 */
public class CoordinatorProcessorDeepAgentPresetTest {

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

    private DeepAgentPreset enabledPreset() {
        when(cfg.getInitParameter(DeepAgentPreset.ENABLED_KEY, false)).thenReturn(true);
        return DeepAgentPreset.install(framework);
    }

    // ---- delegate_task registration gating ----

    @Test
    public void delegateTaskRegisteredWhenPresetOn() {
        var preset = enabledPreset();
        var registry = new DefaultToolRegistry();

        processor.registerPresetDelegation(registry, preset, true, "coord");

        assertTrue(registry.getTool("delegate_task").isPresent(),
                "the preset must register the built-in delegation tool");
        assertEquals("ACTIVE",
                preset.runtimeState().get(DeepAgentPreset.PRIMITIVE_DELEGATION),
                "runtime-state must report delegation as genuinely registered");
    }

    @Test
    public void delegateTaskAbsentWhenPresetOff() {
        var preset = DeepAgentPreset.install(framework);
        var registry = new DefaultToolRegistry();

        processor.registerPresetDelegation(registry, preset, false, "coord");

        assertFalse(registry.getTool("delegate_task").isPresent(),
                "without the preset, delegation must stay opt-in via user @AiTool wrappers");
        assertEquals("INACTIVE(disabled)",
                preset.runtimeState().get(DeepAgentPreset.PRIMITIVE_DELEGATION));
    }

    // ---- outbound governance wrap ----

    @Test
    public void presetOffLeavesFleetUnwrapped() {
        var fleet = StubAgentFleet.builder().agent("worker", "ok").build();

        assertSame(fleet, processor.applyPresetGovernance(fleet, false, framework, "coord"),
                "without the preset the fleet must pass through untouched");
    }

    @Test
    public void presetWrapsFleetWithInstalledPolicies() {
        // The wrap must consume the SAME installed policy chain the inbound
        // pipeline resolves (GovernancePolicies.installed reads this bag).
        props.put(GovernancePolicy.POLICIES_PROPERTY, List.<GovernancePolicy>of(denyAll()));
        var fleet = StubAgentFleet.builder().agent("worker", "should never run").build();

        var governed = processor.applyPresetGovernance(fleet, true, framework, "coord");

        assertInstanceOf(InterceptingAgentFleet.class, governed,
                "the preset must return the interceptor-enforcing wrapper");
        var result = governed.agent("worker").call("default", Map.of("message", "hi"));
        assertFalse(result.success(), "an installed Deny policy must block the dispatch");
        assertTrue(result.text().contains("denied"), result.text());
        assertTrue(result.text().contains("blocked-by-test"),
                "the policy's reason must surface on the synthetic result: " + result.text());
    }

    @Test
    public void presetGovernanceKeepsJournalVisible() {
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
                return "code:" + CoordinatorProcessorDeepAgentPresetTest.class.getName();
            }

            @Override
            public PolicyDecision evaluate(PolicyContext context) {
                return new PolicyDecision.Deny("blocked-by-test");
            }
        };
    }
}
