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
package org.atmosphere.ai.rag.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Auto-configuration that bridges Spring AI's {@link VectorStore} to Atmosphere's
 * {@link org.atmosphere.ai.ContextProvider} SPI.
 *
 * <p>Activates when both {@code spring-ai-vector-store} is on the classpath
 * and a {@link VectorStore} bean is present in the application context.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.vectorstore.VectorStore")
public class AtmosphereRagAutoConfiguration {

    @Bean
    @ConditionalOnBean(VectorStore.class)
    SpringAiVectorStoreContextProvider springAiVectorStoreContextProvider(VectorStore vectorStore) {
        SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
        return new SpringAiVectorStoreContextProvider();
    }
}
