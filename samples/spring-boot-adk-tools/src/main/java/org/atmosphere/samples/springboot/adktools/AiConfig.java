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
package org.atmosphere.samples.springboot.adktools;

import org.atmosphere.ai.budget.StreamingTextBudgetManager;
import org.atmosphere.ai.cache.AiResponseCacheInspector;
import org.atmosphere.ai.filter.CostMeteringFilter;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cpr.BroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration for the ADK Tools sample.
 *
 * <p>Configures token budget management and response caching to demonstrate
 * Atmosphere's AI infrastructure features alongside Google ADK tool calling.</p>
 */
@Configuration
public class AiConfig {

    private static final Logger logger = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public StreamingTextBudgetManager tokenBudgetManager() {
        logger.info("Creating StreamingTextBudgetManager with demo budget");
        var manager = new StreamingTextBudgetManager();
        // Demo budget: 10,000 tokens per user, degrade to cheaper model at 80%
        manager.setBudget(new StreamingTextBudgetManager.Budget(
                "demo-user", 10_000, "gemini-2.0-flash-lite", 0.8));
        return manager;
    }

    @Bean
    public BroadcasterCacheInspector aiCacheInspector() {
        logger.info("Registering AI response cache inspector");
        return new AiResponseCacheInspector();
    }

    @Bean
    public BroadcastFilter costMeteringFilter() {
        logger.info("Registering cost metering filter");
        return new CostMeteringFilter();
    }
}
