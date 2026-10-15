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

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration binds the context's {@link ReactAgent} and
 * {@link ChatModel} to the context's {@link AiEnvironment}, so another context
 * in the JVM keeps its own, and leaves the JVM-wide setters alone.
 */
class AtmosphereSpringAiAlibabaAutoConfigurationEnvironmentTest {

    @AfterEach
    void clearStatics() throws ReflectiveOperationException {
        staticField("staticAgent").set(null, null);
        staticField("staticChatModel").set(null, null);
    }

    private static java.lang.reflect.Field staticField(String name) throws NoSuchFieldException {
        var field = SpringAiAlibabaAgentRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void theContextAgentAndChatModelAreBoundToTheContextApplication() throws ReflectiveOperationException {
        var agent = mock(ReactAgent.class);
        var environment = AiEnvironment.create(key -> null);
        try {
            var beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("aiEnvironment", environment);
            beanFactory.registerSingleton("chatModel", mock(ChatModel.class));
            new AtmosphereSpringAiAlibabaAutoConfiguration(beanFactory.getBeanProvider(AiEnvironment.class))
                    .springAiAlibabaAgentRuntime(agent, beanFactory.getBeanProvider(ChatModel.class));

            assertSame(agent, environment.client(SpringAiAlibabaAgentRuntime.class, ReactAgent.class, null),
                    "the context's agent must serve the context's application");
            assertSame(agent, environment.client(SpringAiAlibabaAgentRuntime.class, ReactAgent.class,
                            mock(ReactAgent.class)),
                    "the application's own agent bean is bound: it wins over the JVM-wide setter");
            assertNotNull(environment.client(SpringAiAlibabaAgentRuntime.class, ChatModel.class, null),
                    "the context's (wrapped) chat model must serve the context's application");
            assertNull(staticField("staticAgent").get(null),
                    "the context's agent must not become every application's");
            assertNull(staticField("staticChatModel").get(null),
                    "the context's chat model must not become every application's");
        } finally {
            environment.close();
        }
    }
}
