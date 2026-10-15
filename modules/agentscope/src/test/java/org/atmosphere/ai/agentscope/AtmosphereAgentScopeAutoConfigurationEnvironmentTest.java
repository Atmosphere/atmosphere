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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration binds the context's {@link ReActAgent} to the
 * context's {@link AiEnvironment}, so another context in the JVM keeps its
 * own, and leaves the JVM-wide setter alone.
 */
class AtmosphereAgentScopeAutoConfigurationEnvironmentTest {

    @AfterEach
    void clearStaticAgent() throws ReflectiveOperationException {
        staticAgentField().set(null, null);
    }

    private static java.lang.reflect.Field staticAgentField() throws NoSuchFieldException {
        var field = AgentScopeAgentRuntime.class.getDeclaredField("staticAgent");
        field.setAccessible(true);
        return field;
    }

    private static ObjectProvider<AiEnvironment> providerOf(AiEnvironment environment) {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("aiEnvironment", environment);
        return beanFactory.getBeanProvider(AiEnvironment.class);
    }

    @Test
    void theContextAgentIsBoundToTheContextApplication() throws ReflectiveOperationException {
        var agent = mock(ReActAgent.class);
        var environment = AiEnvironment.create(key -> null);
        try {
            new AtmosphereAgentScopeAutoConfiguration().agentScopeAgentRuntime(agent, providerOf(environment));

            assertSame(agent, environment.client(AgentScopeAgentRuntime.class, ReActAgent.class, null),
                    "the context's agent must serve the context's application");
            assertSame(agent, environment.client(AgentScopeAgentRuntime.class, ReActAgent.class,
                            mock(ReActAgent.class)),
                    "the application's own agent bean is bound: it wins over the JVM-wide setter");
            assertNull(staticAgentField().get(null),
                    "the context's agent must not become every application's");
        } finally {
            environment.close();
        }
    }
}
