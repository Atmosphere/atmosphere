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
package org.atmosphere.ai.rag.spring;

import org.atmosphere.ai.rag.RagChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Binds {@code atmosphere.ai.rag.chunker} from application config onto
 * {@link RagChunker}'s process-wide default strategy.
 *
 * <p>{@link RagChunker} is a static utility invoked from ingestion code that
 * holds no container handle ({@code InMemoryContextProvider.fromSourceChunked},
 * {@code fromClasspathChunked}, and application {@code chunkAll} calls), so the
 * selected strategy is published to it at context start rather than injected.
 * Default {@code fixed} — the historical fixed-window behaviour, so an
 * application that sets nothing chunks exactly as before.</p>
 *
 * <p>The installed strategy is cleared on context shutdown so a static default
 * never outlives the context that set it (Ownership, Correctness Invariant #1)
 * — which matters for test suites that boot several contexts in one JVM.</p>
 */
@AutoConfiguration
@ConditionalOnClass(RagChunker.class)
public class RagChunkerAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RagChunkerAutoConfiguration.class);

    /**
     * Publishes the configured strategy to {@link RagChunker}.
     *
     * @param strategy the {@code atmosphere.ai.rag.chunker} value
     * @return the installer, which applies the strategy on start and clears it on shutdown
     */
    @Bean
    public RagChunkerStrategyInstaller ragChunkerStrategyInstaller(
            @Value("${atmosphere.ai.rag.chunker:fixed}") String strategy) {
        return new RagChunkerStrategyInstaller(strategy);
    }

    /** Applies the strategy on context start and reverts it symmetrically on shutdown. */
    public static class RagChunkerStrategyInstaller implements InitializingBean, DisposableBean {

        private final String configured;

        RagChunkerStrategyInstaller(String configured) {
            this.configured = configured;
        }

        @Override
        public void afterPropertiesSet() {
            var strategy = RagChunker.Strategy.from(configured);
            RagChunker.setDefaultStrategy(strategy);
            if (strategy != RagChunker.Strategy.FIXED) {
                logger.info("RAG chunking strategy: {} (atmosphere.ai.rag.chunker={})",
                        strategy, configured);
            }
        }

        @Override
        public void destroy() {
            RagChunker.setDefaultStrategy(null);
        }

        /** The strategy this installer resolved — exposed so tests can assert the binding. */
        public RagChunker.Strategy resolvedStrategy() {
            return RagChunker.Strategy.from(configured);
        }
    }
}
