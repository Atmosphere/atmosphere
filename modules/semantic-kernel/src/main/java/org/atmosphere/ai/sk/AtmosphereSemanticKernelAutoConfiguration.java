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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Spring Boot auto-configuration for the Semantic Kernel runtime. Wires a
 * user-supplied {@link ChatCompletionService} bean into the static setter on
 * {@link SemanticKernelAgentRuntime} so the ServiceLoader-discovered runtime
 * picks it up on the next {@code configure()} call.
 *
 * <p>Applications provide the {@link ChatCompletionService} bean themselves —
 * typically via {@code OpenAIChatCompletion.builder()} with an Azure
 * {@code OpenAIAsyncClient} — because SK-Java's service construction depends
 * on provider-specific credentials this module does not depend on.</p>
 */
@AutoConfiguration
@ConditionalOnClass(ChatCompletionService.class)
@ConditionalOnBean(ChatCompletionService.class)
public class AtmosphereSemanticKernelAutoConfiguration {

    @Autowired
    public AtmosphereSemanticKernelAutoConfiguration(ChatCompletionService chatCompletionService) {
        SemanticKernelAgentRuntime.setChatCompletionService(chatCompletionService);
    }
}
