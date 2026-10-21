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
package org.atmosphere.spring.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Stands in for the runtime adapters' post-processors (Spring AI, Spring AI
 * Alibaba, Embabel): registered the way they are, with no order, and reading
 * {@code llm.*} when it runs. It records what it read under
 * {@value #SAW_PREFIX}{@code *}, and only in a test that sets {@value #ENABLED}.
 */
// The adapters implement the org.springframework.boot.env type, deprecated for
// removal in Spring Boot 4.0; the stand-in must be loaded the same way.
@SuppressWarnings({"deprecation", "removal"})
public class LlmReadingPostProcessorStandIn implements EnvironmentPostProcessor {

    static final String ENABLED = "test.llm-reading-stand-in.enabled";
    static final String SAW_PREFIX = "test.llm-reading-stand-in.saw.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"true".equals(environment.getProperty(ENABLED))) {
            return;
        }
        Map<String, Object> saw = new HashMap<>();
        for (var setting : AtmosphereAiLlmPropertyBridge.SETTINGS) {
            var value = environment.getProperty("llm." + setting);
            saw.put(SAW_PREFIX + setting, value == null ? "<unset>" : value);
        }
        environment.getPropertySources().addLast(new MapPropertySource("llm-reading-stand-in", saw));
    }
}
