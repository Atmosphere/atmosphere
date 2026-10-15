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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration binds the context's {@link ChatCompletionService} to
 * the context's {@link AiEnvironment}, so another context in the JVM keeps its
 * own, and leaves the JVM-wide setter alone.
 */
class AtmosphereSemanticKernelAutoConfigurationEnvironmentTest {

    @AfterEach
    void clearStaticService() throws ReflectiveOperationException {
        staticServiceField().set(null, null);
    }

    private static java.lang.reflect.Field staticServiceField() throws NoSuchFieldException {
        var field = SemanticKernelAgentRuntime.class.getDeclaredField("staticService");
        field.setAccessible(true);
        return field;
    }

    private static ObjectProvider<AiEnvironment> providerOf(AiEnvironment environment) {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("aiEnvironment", environment);
        return beanFactory.getBeanProvider(AiEnvironment.class);
    }

    @Test
    void theContextServiceIsBoundToTheContextApplication() throws ReflectiveOperationException {
        var service = mock(ChatCompletionService.class);
        var environment = AiEnvironment.create(key -> null);
        try {
            new AtmosphereSemanticKernelAutoConfiguration(service, providerOf(environment));

            assertSame(service, environment.client(SemanticKernelAgentRuntime.class,
                            ChatCompletionService.class, null),
                    "the context's service must serve the context's application");
            assertSame(service, environment.client(SemanticKernelAgentRuntime.class,
                            ChatCompletionService.class, mock(ChatCompletionService.class)),
                    "the application's own service bean is bound: it wins over the JVM-wide setter");
            assertNull(staticServiceField().get(null),
                    "the context's service must not become every application's");
        } finally {
            environment.close();
        }
    }
}
