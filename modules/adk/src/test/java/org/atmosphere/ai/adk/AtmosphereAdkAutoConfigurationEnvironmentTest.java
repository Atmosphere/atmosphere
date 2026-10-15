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
package org.atmosphere.ai.adk;

import com.google.adk.runner.Runner;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration binds the context's {@link Runner} to the context's
 * {@link AiEnvironment}, so another context in the JVM keeps its own, and
 * leaves the JVM-wide setter alone.
 */
class AtmosphereAdkAutoConfigurationEnvironmentTest {

    @AfterEach
    void clearStaticRunner() throws ReflectiveOperationException {
        staticRunnerField().set(null, null);
    }

    private static java.lang.reflect.Field staticRunnerField() throws NoSuchFieldException {
        var field = AdkAgentRuntime.class.getDeclaredField("staticRunner");
        field.setAccessible(true);
        return field;
    }

    private static ObjectProvider<AiEnvironment> providerOf(AiEnvironment environment) {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("aiEnvironment", environment);
        return beanFactory.getBeanProvider(AiEnvironment.class);
    }

    @Test
    void theContextRunnerIsBoundToTheContextApplication() throws ReflectiveOperationException {
        var runner = mock(Runner.class);
        var environment = AiEnvironment.create(key -> null);
        try {
            new AtmosphereAdkAutoConfiguration().adkAiSupportBridge(runner, providerOf(environment));

            assertSame(runner, environment.client(AdkAgentRuntime.class, Runner.class, null),
                    "the context's Runner must serve the context's application");
            assertSame(runner, environment.client(AdkAgentRuntime.class, Runner.class, mock(Runner.class)),
                    "the application's own Runner bean is bound: it wins over the JVM-wide setter");
            assertNull(staticRunnerField().get(null),
                    "the context's Runner must not become every application's");
        } finally {
            environment.close();
        }
    }
}
