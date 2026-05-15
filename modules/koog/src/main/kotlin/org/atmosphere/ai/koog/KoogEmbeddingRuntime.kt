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
package org.atmosphere.ai.koog

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.EmbeddingRuntime

/**
 * [EmbeddingRuntime] backed by Koog's [LLMEmbeddingProvider].
 *
 * Koog exposes embedding generation through provider-specific LLM clients
 * rather than the agent [PromptExecutor]. Applications install their client
 * with [setEmbeddingProvider]; the runtime then uses the configured
 * [LLModel] for single and batch embeddings.
 */
class KoogEmbeddingRuntime : EmbeddingRuntime {

    companion object {
        @Volatile
        private var embeddingProvider: LLMEmbeddingProvider? = null

        @Volatile
        private var embeddingModel: LLModel = LLModel(LLMProvider.OpenAI, "text-embedding-3-small")

        @JvmStatic
        fun setEmbeddingProvider(provider: LLMEmbeddingProvider?) {
            embeddingProvider = provider
        }

        @JvmStatic
        fun setEmbeddingModel(model: LLModel) {
            embeddingModel = model
        }
    }

    override fun name(): String = "koog"

    override fun isAvailable(): Boolean = embeddingProvider != null || try {
        Class.forName("ai.koog.prompt.executor.clients.LLMEmbeddingProvider")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    override fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "text must not be blank" }
        val provider = embeddingProvider
            ?: throw IllegalStateException(
                "KoogEmbeddingRuntime: LLMEmbeddingProvider not configured. " +
                    "Call KoogEmbeddingRuntime.setEmbeddingProvider() before embedding."
            )
        return runBlocking { provider.embed(text, resolveModel()) }.toFloatArray()
    }

    override fun embedAll(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        texts.forEach { require(it.isNotBlank()) { "texts must not contain blank entries" } }
        val provider = embeddingProvider
            ?: throw IllegalStateException(
                "KoogEmbeddingRuntime: LLMEmbeddingProvider not configured. " +
                    "Call KoogEmbeddingRuntime.setEmbeddingProvider() before embedding."
            )
        return runBlocking { provider.embed(texts, resolveModel()) }.map { it.toFloatArray() }
    }

    private fun resolveModel(): LLModel {
        val configured = AiConfig.get()?.model()
        return if (configured.isNullOrBlank() || configured == embeddingModel.id) {
            embeddingModel
        } else {
            LLModel(embeddingModel.provider, configured, embeddingModel.capabilities,
                embeddingModel.contextLength, embeddingModel.maxOutputTokens)
        }
    }

    private fun List<Double>.toFloatArray(): FloatArray {
        val values = FloatArray(size)
        for (i in indices) {
            values[i] = this[i].toFloat()
        }
        return values
    }
}
