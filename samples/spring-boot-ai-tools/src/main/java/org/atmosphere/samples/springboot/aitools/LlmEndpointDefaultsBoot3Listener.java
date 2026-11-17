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
package org.atmosphere.samples.springboot.aitools;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Applies the endpoint defaults of {@code LlmEndpointDefaults} on Spring Boot 3
 * ({@code -Pspring-boot3}), which neither has
 * {@code org.springframework.boot.EnvironmentPostProcessor} nor reads its
 * {@code spring.factories} key. Registered as an application listener, a type
 * every Spring Boot line loads, it does nothing when that post-processor type is
 * present (Spring Boot 4), where {@code LlmEndpointDefaults} applies them.
 *
 * <p>The type is looked up by name, never loaded. The listener runs after
 * Spring Boot's {@code EnvironmentPostProcessorApplicationListener}
 * ({@code HIGHEST_PRECEDENCE + 10}), so after the Spring Boot 3 starter has
 * mapped {@code atmosphere.ai.*} onto {@code llm.*}, and before the framework
 * adapters' listeners ({@code LOWEST_PRECEDENCE}), which read {@code llm.*}.</p>
 */
public class LlmEndpointDefaultsBoot3Listener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /** The post-processor type of Spring Boot 4, which does not exist on Spring Boot 3. */
    static final String BOOT4_POST_PROCESSOR = "org.springframework.boot.EnvironmentPostProcessor";

    private final boolean boot4PostProcessorPresent;

    public LlmEndpointDefaultsBoot3Listener() {
        this(ClassUtils.isPresent(BOOT4_POST_PROCESSOR, LlmEndpointDefaultsBoot3Listener.class.getClassLoader()));
    }

    LlmEndpointDefaultsBoot3Listener(boolean boot4PostProcessorPresent) {
        this.boot4PostProcessorPresent = boot4PostProcessorPresent;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (!boot4PostProcessorPresent) {
            LlmEndpointDefaultsSupport.apply(event.getEnvironment());
        }
    }

    @Override
    public int getOrder() {
        return LlmEndpointDefaultsSupport.ORDER;
    }
}
