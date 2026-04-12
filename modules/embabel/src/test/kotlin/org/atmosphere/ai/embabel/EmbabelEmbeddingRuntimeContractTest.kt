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
package org.atmosphere.ai.embabel

import com.embabel.common.ai.model.EmbeddingService
import org.atmosphere.ai.EmbeddingRuntime
import org.atmosphere.ai.test.AbstractEmbeddingRuntimeContractTest

/**
 * Concrete TCK subclass for [EmbabelEmbeddingRuntime]. Injects a
 * test-double [EmbeddingService] whose `embed(String)` and
 * `embed(List<String>)` match the base assertion's `vector[0] = text.length`
 * invariant. Exercises the thin pass-through path.
 */
internal class EmbabelEmbeddingRuntimeContractTest : AbstractEmbeddingRuntimeContractTest() {

    override fun createRuntime(): EmbeddingRuntime = EmbabelEmbeddingRuntime()

    override fun installFakeEmbedder(runtime: EmbeddingRuntime) {
        (runtime as EmbabelEmbeddingRuntime).setNativeEmbeddingService(TestEmbabelEmbeddingService)
    }

    private object TestEmbabelEmbeddingService : EmbeddingService {
        override val name: String = "test-embabel"
        override val provider: String = "atmosphere-test"
        override val dimensions: Int = 8
        // Embabel 0.3.4 EmbeddingService extends AiModel<Any>, which requires
        // a `model` property exposing the underlying native client. The
        // contract assertions don't touch this path, so a sentinel string
        // satisfies the compiler without dragging in a real Spring AI
        // EmbeddingModel.
        override val model: Any = "test-embabel-model"
        override fun embed(text: String): FloatArray {
            val vector = FloatArray(8)
            vector[0] = text.length.toFloat()
            return vector
        }
        override fun embed(texts: List<String>): List<FloatArray> =
            texts.map { embed(it) }
    }
}
