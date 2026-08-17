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
package org.atmosphere.samples.springboot.ragchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The chat path belongs to Atmosphere's own {@code AgentRuntime} (selected via
 * {@code LLM_*}), so Spring AI's chat/audio/image/moderation auto-configurations are
 * excluded to keep a single owner of the model call and to let the app boot keyless.
 *
 * <p><strong>{@code OpenAiEmbeddingAutoConfiguration} is deliberately NOT excluded.</strong>
 * It is the only producer of the {@link org.springframework.ai.embedding.EmbeddingModel}
 * bean that {@link VectorStoreConfig} needs to build the vector store. Excluding it — as
 * this sample did until 4.0.67 — silently disabled retrieval: the store was never created,
 * so {@code SpringAiVectorStoreContextProvider} logged "VectorStore not configured" and every
 * answer was ungrounded while still looking plausible. If you add an exclusion here, check it
 * is not the producer of a bean some other configuration is conditional on.</p>
 */
@SpringBootApplication(excludeName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
})
public class RagChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagChatApplication.class, args);
    }
}
