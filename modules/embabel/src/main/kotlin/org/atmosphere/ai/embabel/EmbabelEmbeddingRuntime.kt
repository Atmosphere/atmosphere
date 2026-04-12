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

/**
 * [EmbeddingRuntime] backed by Embabel's [EmbeddingService]. The Embabel
 * surface already matches the Atmosphere SPI 1:1 — `float[] embed(String)`,
 * `List<float[]> embed(List<String>)`, `int getDimensions()` — so this
 * adapter is a thin pass-through that adds `isAvailable()` semantics and
 * priority ordering.
 *
 * The underlying service is injected via [setEmbeddingService] from
 * `AtmosphereEmbabelAutoConfiguration` or programmatic wiring. When no
 * service is set, [isAvailable] returns `false` and
 * `ServiceLoader`-based discovery skips this runtime cleanly.
 */
class EmbabelEmbeddingRuntime : EmbeddingRuntime {

    @Volatile
    private var instanceService: EmbeddingService? = null

    /** Test / programmatic hook: override the injected service on a single instance. */
    fun setNativeEmbeddingService(service: EmbeddingService) {
        this.instanceService = service
    }

    override fun name(): String = "embabel"

    override fun isAvailable(): Boolean = resolve() != null

    override fun dimensions(): Int = resolve()?.dimensions ?: -1

    override fun embed(text: String): FloatArray {
        val service = resolve()
            ?: throw IllegalStateException("Embabel EmbeddingService not configured")
        return service.embed(text)
    }

    override fun embedAll(texts: MutableList<String>): MutableList<FloatArray> {
        val service = resolve()
            ?: throw IllegalStateException("Embabel EmbeddingService not configured")
        if (texts.isEmpty()) {
            return mutableListOf()
        }
        return service.embed(texts as List<String>).toMutableList()
    }

    override fun priority(): Int = 170

    private fun resolve(): EmbeddingService? {
        instanceService?.let { return it }
        return staticService
    }

    companion object {
        @Volatile
        private var staticService: EmbeddingService? = null

        /** Spring auto-config hook: supply the configured Embabel EmbeddingService. */
        @JvmStatic
        fun setEmbeddingService(service: EmbeddingService) {
            staticService = service
        }
    }
}
