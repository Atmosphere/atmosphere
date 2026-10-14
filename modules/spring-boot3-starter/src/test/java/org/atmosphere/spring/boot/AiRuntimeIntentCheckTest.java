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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code atmosphere.ai.runtime} selects nothing — the highest-priority runtime
 * module on the classpath runs. A sample set it to {@code spring-ai} with no
 * Spring AI adapter on its classpath and silently ran the built-in runtime.
 */
class AiRuntimeIntentCheckTest {

    @Test
    void aConfiguredRuntimeThatIsNotRunningIsReported() {
        var warning = AtmosphereAiAutoConfiguration.runtimeIntentWarning("spring-ai", "built-in");
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("atmosphere.ai.runtime=spring-ai is not honored"),
                warning.get());
        assertTrue(warning.get().contains("'built-in'"), warning.get());
    }

    @Test
    void nothingIsReportedWhenUnsetOrMatching() {
        assertTrue(AtmosphereAiAutoConfiguration.runtimeIntentWarning(null, "built-in").isEmpty());
        assertTrue(AtmosphereAiAutoConfiguration.runtimeIntentWarning(" ", "built-in").isEmpty());
        assertTrue(AtmosphereAiAutoConfiguration.runtimeIntentWarning("Spring-AI ", "spring-ai").isEmpty());
    }
}
