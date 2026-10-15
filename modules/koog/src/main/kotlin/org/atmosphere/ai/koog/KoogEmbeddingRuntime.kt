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
import org.atmosphere.ai.AiEnvironment
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

        @Deprecated(
            "Binds for every application in the JVM; bind for one application with setEmbeddingProvider(environment, provider). "
                + "Kept as the process-wide fallback through 4.x; not scheduled for removal."
        )
        @JvmStatic
        fun setEmbeddingProvider(provider: LLMEmbeddingProvider?) {
            embeddingProvider = provider
        }

        /**
         * Install the [LLMEmbeddingProvider] of one application: it wins over
         * [setEmbeddingProvider], which applies to every application in the
         * JVM, for that application. `null` removes the binding.
         */
        @JvmStatic
        fun setEmbeddingProvider(environment: AiEnvironment, provider: LLMEmbeddingProvider?) {
            environment.bindClient(KoogEmbeddingRuntime::class.java, LLMEmbeddingProvider::class.java, provider)
        }

        @JvmStatic
        fun setEmbeddingModel(model: LLModel) {
            embeddingModel = model
        }
    }

    /** The application this instance serves; `null` until [attach]. */
    @Volatile
    private var environment: AiEnvironment? = null

    override fun attach(environment: AiEnvironment) {
        this.environment = environment
    }

    private fun environment(): AiEnvironment = environment ?: AiEnvironment.current()

    /** The provider of this instance's application: its own binding, else the JVM's. */
    private fun provider(): LLMEmbeddingProvider? =
        environment().client(KoogEmbeddingRuntime::class.java, LLMEmbeddingProvider::class.java, embeddingProvider)

    override fun name(): String = "koog"

    /**
     * Available only once an [LLMEmbeddingProvider] is installed. Koog being on
     * the classpath says nothing about whether this runtime can embed: reporting
     * it available there made it outrank the other runtimes and fail every
     * [embed] call with "LLMEmbeddingProvider not configured".
     */
    override fun isAvailable(): Boolean = provider() != null

    override fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "text must not be blank" }
        val provider = provider()
            ?: throw IllegalStateException(
                "KoogEmbeddingRuntime: LLMEmbeddingProvider not configured. " +
                    "Call KoogEmbeddingRuntime.setEmbeddingProvider() before embedding."
            )
        return runBlocking { provider.embed(text, resolveModel()) }.toFloatArray()
    }

    override fun embedAll(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        texts.forEach { require(it.isNotBlank()) { "texts must not contain blank entries" } }
        val provider = provider()
            ?: throw IllegalStateException(
                "KoogEmbeddingRuntime: LLMEmbeddingProvider not configured. " +
                    "Call KoogEmbeddingRuntime.setEmbeddingProvider() before embedding."
            )
        return runBlocking { provider.embed(texts, resolveModel()) }.map { it.toFloatArray() }
    }

    private fun resolveModel(): LLModel {
        val configured = environment().settings()?.model()
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
