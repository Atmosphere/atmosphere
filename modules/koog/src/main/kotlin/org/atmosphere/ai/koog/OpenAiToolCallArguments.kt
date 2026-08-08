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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Repairs the `tool_calls[].function.arguments` field of an OpenAI
 * chat-completions request body before it leaves the process.
 *
 * ## Why this exists
 *
 * Koog 1.0.0's `AbstractOpenAILLMClient` is asymmetric across the tool-call
 * round trip. Reading a provider response it builds the history entry from a
 * *parsed* object:
 *
 * ```
 * MessagePart.Tool.Call(id, name, Json.parseToJsonElement(arguments).jsonObject)
 * ```
 *
 * so `MessagePart.Tool.Call.args` holds the JSON **text** `{"city":"Paris"}`.
 * Writing that same history entry back out (`convertPromptToMessages`) it then
 * does:
 *
 * ```
 * OpenAIFunction(call.tool, Json.encodeToString(String.serializer(), call.args))
 * ```
 *
 * `String.serializer()` quotes and escapes, so the wire carries
 * `"arguments":"\"{\\\"city\\\":\\\"Paris\\\"}\""` — a JSON string whose content
 * is *another* JSON string, one encode too many. The OpenAI wire format requires
 * `arguments` to be a JSON string holding an **object**; parsing the doubly
 * encoded form yields a string. Lenient servers echo it back to the model;
 * strict ones (Ollama's `/v1` surface) reject the whole request with
 * `400 {"error":{"message":"invalid tool call arguments"}}`, which kills every
 * Koog agent turn that follows a tool call.
 *
 * ## What the repair does
 *
 * Only the exact corrupted shape is rewritten: an `arguments` value that parses
 * to a JSON **string** which itself parses to a JSON **object** loses one layer
 * of encoding. A correctly encoded `arguments` parses straight to an object and
 * is left byte-for-byte alone, so the pass is idempotent and cannot damage a
 * well-formed request (Correctness Invariant #4 — encode correctly at the wire
 * boundary). A body that is not parseable JSON, or that carries no repairable
 * tool call, is returned as the identical instance — nothing is re-serialised
 * unless something actually changed.
 */
internal object OpenAiToolCallArguments {

    private val logger = LoggerFactory.getLogger(OpenAiToolCallArguments::class.java)

    /**
     * Return [body] with every doubly encoded `tool_calls[].function.arguments`
     * collapsed to a single encoding, or [body] itself when there is nothing to
     * repair.
     */
    fun normalizeChatRequest(body: String): String {
        val root = parseOrNull(body) as? JsonObject ?: return body
        val messages = root["messages"] as? JsonArray ?: return body

        var changed = false
        val repairedMessages = ArrayList<JsonElement>(messages.size)
        for (message in messages) {
            val repaired = repairMessage(message)
            if (repaired == null) {
                repairedMessages.add(message)
            } else {
                repairedMessages.add(repaired)
                changed = true
            }
        }
        if (!changed) {
            return body
        }
        logger.debug("Repaired doubly encoded tool-call arguments in an OpenAI chat request")
        return Json.encodeToString(
            JsonElement.serializer(),
            JsonObject(root + ("messages" to JsonArray(repairedMessages)))
        )
    }

    /** Repaired copy of [message], or `null` when it needs no repair. */
    private fun repairMessage(message: JsonElement): JsonElement? {
        val obj = message as? JsonObject ?: return null
        val calls = obj["tool_calls"] as? JsonArray ?: return null

        var changed = false
        val repairedCalls = ArrayList<JsonElement>(calls.size)
        for (call in calls) {
            val repaired = repairToolCall(call)
            if (repaired == null) {
                repairedCalls.add(call)
            } else {
                repairedCalls.add(repaired)
                changed = true
            }
        }
        return if (changed) JsonObject(obj + ("tool_calls" to JsonArray(repairedCalls))) else null
    }

    /** Repaired copy of [call], or `null` when it needs no repair. */
    private fun repairToolCall(call: JsonElement): JsonElement? {
        val callObj = call as? JsonObject ?: return null
        val function = callObj["function"] as? JsonObject ?: return null
        val arguments = function["arguments"] as? JsonPrimitive ?: return null
        if (!arguments.isString) return null
        val unwrapped = unwrapDoubleEncoded(arguments.content) ?: return null
        val repairedFunction = JsonObject(function + ("arguments" to JsonPrimitive(unwrapped)))
        return JsonObject(callObj + ("function" to repairedFunction))
    }

    /**
     * When [encoded] is a JSON string literal whose content is itself a JSON
     * object, return that inner object text; otherwise `null` (already correct,
     * or not repairable without guessing).
     */
    internal fun unwrapDoubleEncoded(encoded: String): String? {
        val outer = parseOrNull(encoded)
        if (outer !is JsonPrimitive || !outer.isString) return null
        val inner = outer.content
        return if (parseOrNull(inner) is JsonObject) inner else null
    }

    private fun parseOrNull(text: String): JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            // Not JSON (or not well-formed JSON): nothing to repair, and the
            // body must reach the provider untouched so its own error surfaces.
            logger.trace("Not parseable as JSON, leaving untouched", e)
            null
        }
}
