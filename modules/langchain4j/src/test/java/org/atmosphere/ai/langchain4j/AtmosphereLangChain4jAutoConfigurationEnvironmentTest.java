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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration offers the context's {@link StreamingChatModel} to
 * the context's {@link AiEnvironment}: it serves that application unless the
 * application set a model itself ({@code LangChain4jAgentRuntime.setModel}),
 * and it never becomes every application's.
 */
class AtmosphereLangChain4jAutoConfigurationEnvironmentTest {

    @AfterEach
    void clearStaticModel() throws ReflectiveOperationException {
        staticModelField().set(null, null);
        AgentRuntimeResolver.clearExplicitClientBinding();
    }

    private static java.lang.reflect.Field staticModelField() throws NoSuchFieldException {
        var field = LangChain4jAgentRuntime.class.getDeclaredField("staticModel");
        field.setAccessible(true);
        return field;
    }

    private static ObjectProvider<AiEnvironment> providerOf(AiEnvironment environment) {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("aiEnvironment", environment);
        return beanFactory.getBeanProvider(AiEnvironment.class);
    }

    @Test
    void theContextModelIsOfferedToTheContextApplication() throws ReflectiveOperationException {
        var model = mock(StreamingChatModel.class);
        var environment = AiEnvironment.create(key -> null);
        try {
            new AtmosphereLangChain4jAutoConfiguration().langChain4jAiSupportBridge(model, providerOf(environment));

            assertSame(model, environment.client(LangChain4jAgentRuntime.class, StreamingChatModel.class, null),
                    "the context's model must serve the context's application");
            var setByTheApplication = mock(StreamingChatModel.class);
            assertSame(setByTheApplication, environment.client(LangChain4jAgentRuntime.class,
                            StreamingChatModel.class, setByTheApplication),
                    "the context's model is offered: a model the application sets itself wins over it");
            assertTrue(environment.hasExplicitBinding(), "the demo runtime must yield to the context's model");
            assertNull(staticModelField().get(null),
                    "the context's model must not become every application's");
        } finally {
            environment.close();
        }
    }
}
