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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON-wire shape of serialized {@link ChatMessage} entries.
 *
 * <p>The {@code name} field on tool messages is load-bearing for interop
 * with stricter OpenAI-compatible endpoints — in particular Google's
 * Gemini {@code v1beta/openai} compatibility layer, which maps the tool
 * message to a native {@code function_response} requiring a non-empty
 * {@code name}. OpenAI itself treats the field as optional, so emitting
 * it when present is purely additive and broadens downstream interop.</p>
 */
class ChatMessageSerializationTest {

    @Test
    void userMessageOmitsToolFields() {
        var wire = OpenAiCompatibleClient.serializeMessage(ChatMessage.user("Hi"));

        assertEquals("user", wire.get("role"));
        assertEquals("Hi", wire.get("content"));
        assertFalse(wire.containsKey("tool_call_id"),
                "non-tool messages must not carry tool_call_id");
        assertFalse(wire.containsKey("name"),
                "non-tool messages must not carry a function name");
    }

    @Test
    void toolMessageWithoutNameOmitsNameField() {
        // Backwards-compat path: callers who predate the name field still
        // produce a valid OpenAI payload without the optional key.
        var wire = OpenAiCompatibleClient.serializeMessage(
                ChatMessage.tool("22°C", "call-1"));

        assertEquals("tool", wire.get("role"));
        assertEquals("22°C", wire.get("content"));
        assertEquals("call-1", wire.get("tool_call_id"));
        assertFalse(wire.containsKey("name"),
                "tool messages without a function name must not emit an empty name");
    }

    @Test
    void toolMessageWithNameEmitsNameField() {
        // Load-bearing for Gemini compat: the name must appear on the
        // wire so Gemini's OpenAI-compatibility layer can populate its
        // native function_response.name.
        var wire = OpenAiCompatibleClient.serializeMessage(
                ChatMessage.tool("22°C", "call-1", "get_weather"));

        assertEquals("tool", wire.get("role"));
        assertEquals("22°C", wire.get("content"));
        assertEquals("call-1", wire.get("tool_call_id"));
        assertEquals("get_weather", wire.get("name"),
                "Gemini compat requires this field to be non-empty on tool messages");
    }

    @Test
    void nonToolMessageWithAccidentalNameSuppressesIt() {
        // A caller who somehow constructs a system/user/assistant ChatMessage
        // with a name set must NOT emit it — the name field is only valid
        // on tool messages in the chat-completions spec.
        var msg = new ChatMessage("user", "Hi", null, "leaked");
        var wire = OpenAiCompatibleClient.serializeMessage(msg);

        assertEquals("user", wire.get("role"));
        assertFalse(wire.containsKey("name"),
                "name only appears on tool messages regardless of the record field");
    }

    @Test
    void assistantToolCallsSerializesCompleteFunctionCallArray() {
        // Critical for Gemini's OpenAI-compat layer: the preceding assistant
        // message must carry the tool_calls array so each subsequent tool
        // message can pair with its originating function_call.
        var msg = ChatMessage.assistantToolCalls(java.util.List.of(
                new ChatMessage.ToolCall("call_xyz", "schedule_meeting",
                        "{\"topic\":\"sync\",\"date_hint\":\"tomorrow\"}")));
        var wire = OpenAiCompatibleClient.serializeMessage(msg);

        assertEquals("assistant", wire.get("role"));
        assertFalse(wire.containsKey("content"),
                "assistant tool-call messages have null content, not empty content");
        @SuppressWarnings("unchecked")
        var calls = (java.util.List<java.util.Map<String, Object>>) wire.get("tool_calls");
        assertEquals(1, calls.size());
        var call = calls.get(0);
        assertEquals("call_xyz", call.get("id"));
        assertEquals("function", call.get("type"));
        @SuppressWarnings("unchecked")
        var fn = (java.util.Map<String, Object>) call.get("function");
        assertEquals("schedule_meeting", fn.get("name"));
        assertEquals("{\"topic\":\"sync\",\"date_hint\":\"tomorrow\"}", fn.get("arguments"));
    }

    @Test
    void assistantToolCallsNullArgumentsDefaultsToEmptyObject() {
        // Defensive: a tool call with null argumentsJson must still
        // serialize to a well-formed JSON string rather than emitting null,
        // which OpenAI rejects as a schema violation.
        var msg = ChatMessage.assistantToolCalls(java.util.List.of(
                new ChatMessage.ToolCall("call_abc", "get_time", null)));
        var wire = OpenAiCompatibleClient.serializeMessage(msg);
        @SuppressWarnings("unchecked")
        var calls = (java.util.List<java.util.Map<String, Object>>) wire.get("tool_calls");
        @SuppressWarnings("unchecked")
        var fn = (java.util.Map<String, Object>) calls.get(0).get("function");
        assertEquals("{}", fn.get("arguments"));
    }

    @Test
    void keyOrderStable() {
        // LinkedHashMap insertion order — verifies the wire produces a
        // stable JSON field order that's friendly to diff-based tests.
        var wire = OpenAiCompatibleClient.serializeMessage(
                ChatMessage.tool("r", "c", "fn"));
        var keys = wire.keySet().iterator();
        assertEquals("role", keys.next());
        assertEquals("content", keys.next());
        assertEquals("tool_call_id", keys.next());
        assertEquals("name", keys.next());
        assertTrue(keys.hasNext() == false);
    }
}
