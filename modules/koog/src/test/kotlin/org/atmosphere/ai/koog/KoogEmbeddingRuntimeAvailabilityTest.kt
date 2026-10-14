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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Availability must mean "can embed". Koog on the classpath with no
 * [LLMEmbeddingProvider] installed used to report available, outrank the
 * Built-in runtime, and fail every embed with "LLMEmbeddingProvider not
 * configured" — once per turn in the embedding scope guardrail.
 */
internal class KoogEmbeddingRuntimeAvailabilityTest {

    @AfterEach
    fun clearProvider() {
        KoogEmbeddingRuntime.setEmbeddingProvider(null)
    }

    @Test
    fun `classpath presence alone does not make the runtime available`() {
        KoogEmbeddingRuntime.setEmbeddingProvider(null)
        assertFalse(KoogEmbeddingRuntime().isAvailable())
    }

    @Test
    fun `an installed provider makes the runtime available`() {
        KoogEmbeddingRuntime.setEmbeddingProvider(object : LLMEmbeddingProvider() {
            override suspend fun embed(text: String, model: LLModel): List<Double> = listOf(1.0)

            override suspend fun embed(texts: List<String>, model: LLModel): List<List<Double>> =
                texts.map { listOf(1.0) }
        })
        assertTrue(KoogEmbeddingRuntime().isAvailable())
    }
}
