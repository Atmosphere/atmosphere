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
package org.atmosphere.samples.springboot.springairouting;

import org.atmosphere.ai.filter.ContentSafetyFilter;
import org.atmosphere.ai.filter.CostMeteringFilter;
import org.atmosphere.cpr.BroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Spring Boot configuration for the Spring AI Routing sample.
 *
 * <p>Registers Atmosphere AI broadcast filters for content safety and cost metering.</p>
 */
@Configuration
public class AiFilterConfig {

    private static final Logger logger = LoggerFactory.getLogger(AiFilterConfig.class);

    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
            "hack", "exploit", "malware", "ransomware", "phishing"
    );

    @Bean
    public BroadcastFilter contentSafetyFilter() {
        logger.info("Registering content safety filter with keyword checker");
        return new ContentSafetyFilter(text -> {
            var lower = text.toLowerCase();
            for (var keyword : BLOCKED_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return new ContentSafetyFilter.SafetyResult.Unsafe(
                            "Content blocked: contains prohibited keyword '" + keyword + "'");
                }
            }
            return new ContentSafetyFilter.SafetyResult.Safe();
        });
    }

    @Bean
    public BroadcastFilter costMeteringFilter() {
        logger.info("Registering cost metering filter");
        return new CostMeteringFilter();
    }
}
