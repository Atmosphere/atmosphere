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

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

/**
 * Koog's stable [OpenAILLMClient] with the tool-call argument encoding repaired
 * on the way out.
 *
 * Koog 1.0.0 re-encodes an already-serialised `MessagePart.Tool.Call.args`
 * through `String.serializer()` when it rebuilds the request, so a second
 * turn after any tool call ships
 * `"arguments":"\"{\\\"city\\\":\\\"Paris\\\"}\""`. The OpenAI wire format
 * requires that field to decode to an object; strict servers (Ollama's `/v1`
 * surface) answer `400 invalid tool call arguments` and the agent loop dies at
 * the first follow-up turn. See [OpenAiToolCallArguments] for the full trace.
 *
 * The repair is applied at the single serialisation seam Koog routes **both**
 * dispatch modes through — `getResponse` (blocking) and `executeStreaming` both
 * call `serializeProviderChatRequest` — so streaming and non-streaming carry
 * identical bytes (Correctness Invariant #7 — Mode Parity).
 */
open class AtmosphereOpenAILLMClient(
    apiKey: String,
    settings: OpenAIClientSettings = OpenAIClientSettings()
) : OpenAILLMClient(apiKey, settings, HttpClientFactoryResolver.resolve()) {

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean
    ): String = OpenAiToolCallArguments.normalizeChatRequest(
        super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream)
    )
}
