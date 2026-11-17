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
package org.atmosphere.ai.spring.alibaba;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Runs the {@code llm.*} &rarr; {@code spring.ai.openai.*} mapping of
 * {@link AtmosphereSpringAiAlibabaEnvironmentPostProcessor} on Spring Boot 3, which
 * neither has {@code org.springframework.boot.EnvironmentPostProcessor} nor
 * reads its {@code spring.factories} key. Registered as an application listener,
 * a type every Spring Boot line loads, it does nothing when that post-processor
 * type is present (Spring Boot 4), where the post-processor runs the mapping.
 *
 * <p>The type is looked up by name, never loaded. The listener runs after
 * Spring Boot's {@code EnvironmentPostProcessorApplicationListener}
 * ({@code HIGHEST_PRECEDENCE + 10}), once {@code application.yml} is loaded and
 * the Spring Boot 3 starter has mapped {@code atmosphere.ai.*} onto
 * {@code llm.*}, and before the application context is created.</p>
 */
public class AtmosphereSpringAiAlibabaBoot3EnvironmentListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /** The post-processor type of Spring Boot 4, which does not exist on Spring Boot 3. */
    static final String BOOT4_POST_PROCESSOR = "org.springframework.boot.EnvironmentPostProcessor";

    private final boolean boot4PostProcessorPresent;

    public AtmosphereSpringAiAlibabaBoot3EnvironmentListener() {
        this(ClassUtils.isPresent(BOOT4_POST_PROCESSOR,
                AtmosphereSpringAiAlibabaBoot3EnvironmentListener.class.getClassLoader()));
    }

    AtmosphereSpringAiAlibabaBoot3EnvironmentListener(boolean boot4PostProcessorPresent) {
        this.boot4PostProcessorPresent = boot4PostProcessorPresent;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (!boot4PostProcessorPresent) {
            SpringAiAlibabaLlmBridge.apply(event.getEnvironment());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
