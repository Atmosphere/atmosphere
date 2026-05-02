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
package org.atmosphere.samples.quarkus.aichat;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Drops {@code frequency_penalty} / {@code presence_penalty} from the OpenAI
 * builder before {@code build()}. Gemini's OpenAI-compatible endpoint at
 * {@code generativelanguage.googleapis.com/v1beta/openai/} rejects the request
 * with {@code "Invalid JSON payload received. Unknown name 'frequency_penalty':
 * Cannot find field"} when those fields are present, even at value 0.
 *
 * <p>{@link ModelBuilderCustomizer} is Quarkus LangChain4j's documented hook
 * for tweaking the underlying provider builder right before {@code build()}.
 * The bean is automatically picked up by the OpenAI deployment processor.</p>
 *
 * <p>Drop this class if you point the sample at OpenAI proper or any provider
 * that natively supports the penalty fields.</p>
 */
@ApplicationScoped
public class GeminiCompatCustomizer
        implements ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {

    @Override
    public void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
        builder.frequencyPenalty(null);
        builder.presencePenalty(null);
    }
}
