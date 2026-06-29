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
package org.atmosphere.checkpoint.spring;

import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that provides a safe default {@link CheckpointStore} when
 * the {@code atmosphere-checkpoint} JAR is on the classpath and the application
 * supplies none of its own.
 *
 * <p>Mirrors the permissive-default-with-warning idiom used elsewhere in the
 * framework (e.g. {@link org.atmosphere.spring.boot.DurableSessionAutoConfiguration}
 * for session stores and {@code AiGatewayHolder} for the outbound LLM gateway):
 * the bean materializes so dependent surfaces (the admin control plane's
 * passivation views, durable workflow execution) have a live, callable store out
 * of the box, while a startup {@code WARN} makes the development-grade choice
 * visible so it is never mistaken for a production configuration.</p>
 *
 * <p>The default is gated with {@link ConditionalOnMissingBean} on
 * {@link CheckpointStore}: an operator-supplied {@code CheckpointStore} {@code @Bean}
 * (in-memory, SQLite via {@code SqliteCheckpointStore}, or Postgres via
 * {@code atmosphere-checkpoint-postgres}) always wins, and this default backs off
 * entirely — it never overrides real configuration.</p>
 *
 * <p>Spring owns the bean's lifecycle: {@code start()} runs on initialization and
 * {@code stop()} on context shutdown (creator-owns-lifecycle — the framework never
 * starts or stops a store it did not create).</p>
 */
@AutoConfiguration
@ConditionalOnClass(CheckpointStore.class)
public class AtmosphereCheckpointAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereCheckpointAutoConfiguration.class);

    /**
     * Default in-memory checkpoint store, used only when no other
     * {@link CheckpointStore} bean is present.
     *
     * @return a started {@link InMemoryCheckpointStore}
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean(CheckpointStore.class)
    public CheckpointStore checkpointStore() {
        logger.warn("No CheckpointStore configured — using in-memory (lost on restart); "
                + "configure SQLite (SqliteCheckpointStore from atmosphere-checkpoint) or "
                + "Postgres (atmosphere-checkpoint-postgres) for production.");
        return new InMemoryCheckpointStore();
    }
}
