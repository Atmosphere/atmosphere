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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.memory.MemoryExtractionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the assistant's long-term memory at startup. Builds an
 * {@link InMemoryLongTermMemory} (zero external deps; facts live for the JVM's
 * lifetime and persist across WebSocket reconnects for the same user) and a
 * framework {@link LongTermMemoryInterceptor} that recalls those facts into the
 * system prompt before each turn and extracts new facts when a session closes.
 *
 * <p>The {@link LongTermMemory} backend is published as a Spring {@code @Bean}
 * for direct injection (and test assertions), AND the built interceptor is
 * registered into {@link LongTermMemoryHolder} for the no-arg
 * {@link PersonalAssistantMemoryInterceptor} that the
 * {@code @AiEndpoint(interceptors=...)} scanner instantiates reflectively (no
 * Spring DI on the interceptor side). This mirrors how {@link RemoteToolsConfig}
 * publishes the {@code McpToolSource} for {@link McpToolsInterceptor}.</p>
 *
 * <p>The extraction runtime is the same {@code AgentRuntime} the LLM path
 * resolves ({@link AgentRuntimeResolver#resolve()}, highest-priority available);
 * fact extraction reuses it rather than standing up a separate model. With the
 * {@link MemoryExtractionStrategy#onSessionClose() session-close} strategy the
 * extraction LLM call happens once per session (at disconnect), not per
 * message.</p>
 */
@Configuration
public class LongTermMemoryConfig implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(LongTermMemoryConfig.class);

    /** Cap on facts kept per user — also the cap injected into the prompt. */
    private static final int MAX_FACTS = 20;

    @Bean
    public LongTermMemory longTermMemory() {
        var memory = new InMemoryLongTermMemory(MAX_FACTS);
        // Reuse the same runtime the LLM dispatch path resolves — the
        // session-close strategy turns the conversation into a small set of
        // durable facts using one extraction call at disconnect.
        var runtime = AgentRuntimeResolver.resolve();
        var interceptor = new LongTermMemoryInterceptor(
                memory,
                MemoryExtractionStrategy.onSessionClose(),
                runtime,
                MAX_FACTS);
        LongTermMemoryHolder.set(interceptor, memory);
        LOG.info("Long-term memory wired (InMemoryLongTermMemory, maxFacts={}, "
                + "extractionRuntime={}, strategy=onSessionClose)", MAX_FACTS, runtime.name());
        return memory;
    }

    @Override
    public void destroy() {
        LongTermMemoryHolder.clear();
    }
}
