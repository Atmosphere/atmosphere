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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.AiEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AiChatApplication {

    private static final Logger logger = LoggerFactory.getLogger(AiChatApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AiChatApplication.class, args);
    }

    /**
     * Wave 1 showcase: log the resolved {@code AgentRuntime} and its
     * runtime-truth {@code models()} list at startup. Wave 5 showcase:
     * log the discovered {@code EmbeddingRuntime} chain so operators can
     * confirm which embedding adapter will serve RAG or SPI calls. Both are
     * this application's: the {@link AiEnvironment} the Atmosphere starter
     * registers for the context.
     */
    @Bean
    CommandLineRunner logRuntimeDiscovery(AiEnvironment aiEnvironment) {
        return args -> {
            var runtime = aiEnvironment.runtimes().resolve();
            logger.info("AgentRuntime resolved: {} (priority {}) models={}",
                    runtime.name(), runtime.priority(), runtime.models());
            var embeddings = aiEnvironment.embeddings().resolveAll();
            if (embeddings.isEmpty()) {
                logger.info("No EmbeddingRuntime discovered on the classpath");
            } else {
                logger.info("EmbeddingRuntime chain: {}",
                        embeddings.stream().map(e -> e.name() + "@" + e.priority()).toList());
            }
        };
    }

    // No root page or view controller here, on purpose: the starter's root
    // redirect then sends GET / to the Atmosphere Console with the query string
    // kept, so /?token=admin lands on /atmosphere/console/?token=admin. A view
    // controller or a static index.html at / would answer first and drop it.
}
