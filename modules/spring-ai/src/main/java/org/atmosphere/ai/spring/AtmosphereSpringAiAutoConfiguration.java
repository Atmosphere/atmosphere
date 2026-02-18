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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AiStreamingAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Spring AI streaming adapter.
 * Activates when {@code spring-ai-model} is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
public class AtmosphereSpringAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringAiStreamingAdapter springAiStreamingAdapter() {
        return new SpringAiStreamingAdapter();
    }
}
