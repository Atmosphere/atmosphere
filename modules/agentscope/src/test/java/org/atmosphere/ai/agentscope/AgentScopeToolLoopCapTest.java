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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.llm.ToolLoopPolicies;
import org.atmosphere.ai.llm.ToolLoopPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins that an attached {@link ToolLoopPolicy} bounds the AgentScope loop.
 *
 * <p>AgentScope opens a single {@code ModelCallScope} per execute, so its
 * internal rounds never surface as {@code onModelStart} and Atmosphere's
 * {@code ToolLoopGuard} counts at most one per dispatch — it cannot enforce a
 * round cap here no matter what policy is attached. The only binding cap is
 * AgentScope's own {@code maxIters}, so the policy has to be translated onto it
 * during the per-request agent rebuild. This is the rare case where the cap is
 * genuinely per-request: the ADK equivalent is process-wide because that agent
 * is assembled once.</p>
 */
class AgentScopeToolLoopCapTest {

    private static final int BASE_MAX_ITERS = 12;

    private static ReActAgent baseAgent() {
        return ReActAgent.builder()
                .name("base")
                .sysPrompt("you are a fixture")
                .maxIters(BASE_MAX_ITERS)
                .build();
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "hello", "sys", "model", null, "sess", null, null,
                List.of(), null, null, null, Map.of(), List.of(), null, null);
    }

    @Test
    void anAttachedPolicyOverridesTheAgentsOwnMaxIters() {
        var policy = ToolLoopPolicy.maxIterations(3);
        var context = ToolLoopPolicies.attach(context(), policy);

        var rebuilt = AgentScopeAgentRuntime.rebuildAgent(baseAgent(), context, null, null);

        assertEquals(3, rebuilt.getMaxIters(),
                "the per-request policy must bind — ToolLoopGuard cannot count "
                        + "AgentScope's internal rounds, so this knob is the only cap");
        assertNotEquals(BASE_MAX_ITERS, rebuilt.getMaxIters(),
                "the base agent's value must not win over an explicit policy");
    }

    @Test
    void withNoPolicyTheAgentsOwnTuningIsPreserved() {
        var rebuilt = AgentScopeAgentRuntime.rebuildAgent(baseAgent(), context(), null, null);

        assertEquals(BASE_MAX_ITERS, rebuilt.getMaxIters(),
                "an unconfigured request must not silently retune a user's agent");
    }

    @Test
    void theCapIsPerRequestNotSticky() {
        var base = baseAgent();

        var capped = AgentScopeAgentRuntime.rebuildAgent(
                base, ToolLoopPolicies.attach(context(), ToolLoopPolicy.maxIterations(2)),
                null, null);
        var uncapped = AgentScopeAgentRuntime.rebuildAgent(base, context(), null, null);

        assertEquals(2, capped.getMaxIters());
        assertEquals(BASE_MAX_ITERS, uncapped.getMaxIters(),
                "one capped request must not leak its cap into the next — the base "
                        + "agent is shared across dispatches");
    }
}
