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
import org.atmosphere.ai.EmbeddingRuntime
import org.atmosphere.ai.test.AbstractEmbeddingRuntimeContractTest

internal class KoogEmbeddingRuntimeContractTest : AbstractEmbeddingRuntimeContractTest() {

    override fun createRuntime(): EmbeddingRuntime = KoogEmbeddingRuntime()

    override fun installFakeEmbedder(runtime: EmbeddingRuntime) {
        KoogEmbeddingRuntime.setEmbeddingProvider(TestEmbeddingProvider)
    }

    private object TestEmbeddingProvider : LLMEmbeddingProvider() {
        override suspend fun embed(text: String, model: LLModel): List<Double> {
            return vector(text)
        }

        override suspend fun embed(texts: List<String>, model: LLModel): List<List<Double>> {
            return texts.map { vector(it) }
        }

        private fun vector(text: String): List<Double> {
            return listOf(text.length.toDouble(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }
}
