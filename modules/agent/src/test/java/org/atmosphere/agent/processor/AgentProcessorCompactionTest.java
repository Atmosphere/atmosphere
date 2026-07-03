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
package org.atmosphere.agent.processor;

import org.atmosphere.ai.CompactionConfig;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.LlmSummarizingCompaction;
import org.atmosphere.ai.SlidingWindowCompaction;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code org.atmosphere.ai.compaction} seam on the {@code @Agent}
 * memory path — same selection semantics as the {@code @AiEndpoint} and
 * {@code @Coordinator} paths (Correctness Invariant #7, Mode Parity).
 */
public class AgentProcessorCompactionTest {

    private AtmosphereFramework frameworkWith(String strategy) {
        var framework = mock(AtmosphereFramework.class);
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(CompactionConfig.STRATEGY_KEY)).thenReturn(strategy);
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
        return framework;
    }

    @Test
    public void compactionSeamSelectsLlmSummarizing() {
        var memory = new AgentProcessor().resolveMemory(10, frameworkWith("summarizing"));

        assertInstanceOf(InMemoryConversationMemory.class, memory);
        assertInstanceOf(LlmSummarizingCompaction.class,
                ((InMemoryConversationMemory) memory).compactionStrategy());
        assertEquals(10, memory.maxMessages());
    }

    @Test
    public void compactionDefaultsToSlidingWindow() {
        var memory = new AgentProcessor().resolveMemory(10, frameworkWith(null));

        assertInstanceOf(InMemoryConversationMemory.class, memory);
        assertInstanceOf(SlidingWindowCompaction.class,
                ((InMemoryConversationMemory) memory).compactionStrategy());
    }
}
