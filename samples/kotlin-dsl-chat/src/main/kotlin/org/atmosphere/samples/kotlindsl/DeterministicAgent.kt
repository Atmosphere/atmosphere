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
package org.atmosphere.samples.kotlindsl

/**
 * A deterministic, offline "agent". No API key and no network: replies are
 * computed by simple rules so the sample — and its delivery test — are fully
 * reproducible.
 *
 * Swap this for a real `AgentRuntime` / `@AiEndpoint` (LangChain4j, Spring AI,
 * Anthropic, ...) when you want live model output; the Kotlin DSL wiring in
 * [KotlinDslChat] stays exactly the same.
 */
class DeterministicAgent {

    /**
     * Produces a reply for the given user message. Pure and side-effect free so
     * the same input always yields the same output.
     */
    fun reply(message: String): String {
        val text = message.trim()
        return when {
            text.isEmpty() -> "Say something and I'll reply."
            text.equals("ping", ignoreCase = true) -> "pong"
            text.endsWith("?") -> "You asked: \"$text\" — here is a deterministic answer."
            else -> "echo: $text"
        }
    }
}
