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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.BuiltInAiSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Resolves the best available {@link AiSupport} implementation using
 * {@link ServiceLoader}. Filters by {@link AiSupport#isAvailable()},
 * sorts by {@link AiSupport#priority()} descending, and returns the
 * highest-priority match. Falls back to {@link BuiltInAiSupport} if
 * no other implementation is available.
 */
public final class DefaultAiSupportResolver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAiSupportResolver.class);

    private DefaultAiSupportResolver() {
    }

    /**
     * Resolve the best available {@link AiSupport}.
     *
     * @return the highest-priority available AiSupport, or {@link BuiltInAiSupport}
     */
    public static AiSupport resolve() {
        var loader = ServiceLoader.load(AiSupport.class);

        var best = loader.stream()
                .map(ServiceLoader.Provider::get)
                .filter(support -> {
                    var available = support.isAvailable();
                    logger.debug("AiSupport '{}': available={}, priority={}",
                            support.name(), available, support.priority());
                    return available;
                })
                .max(Comparator.comparingInt(AiSupport::priority));

        if (best.isPresent()) {
            logger.info("Resolved AiSupport: {} (priority={})",
                    best.get().name(), best.get().priority());
            return best.get();
        }

        logger.info("No AiSupport found on classpath, falling back to built-in");
        return new BuiltInAiSupport();
    }
}
