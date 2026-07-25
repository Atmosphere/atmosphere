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
package org.atmosphere.ai.openai;

import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseMapsRolesSystemPromptAndFinalUserMessage() {
        var inbound = OpenAiWire.parse("""
                {"model":"demo","stream":true,"user":"alice","messages":[
                  {"role":"system","content":"Be terse."},
                  {"role":"user","content":"Hi"},
                  {"role":"assistant","content":"Hello!"},
                  {"role":"user","content":"What is Atmosphere?"}
                ]}""");

        assertEquals("demo", inbound.model());
        assertTrue(inbound.stream());
        assertEquals("alice", inbound.user());
        assertEquals("What is Atmosphere?", inbound.userMessage());
        assertEquals(3, inbound.priorTurns().size());
        assertEquals("system", inbound.priorTurns().get(0).role());
        assertEquals("Be terse.", inbound.priorTurns().get(0).content());
        assertEquals("user", inbound.priorTurns().get(1).role());
        assertEquals("assistant", inbound.priorTurns().get(2).role());
    }

    @Test
    void parseMapsDeveloperRoleToSystemAndTextContentParts() {
        var inbound = OpenAiWire.parse("""
                {"messages":[
                  {"role":"developer","content":"Context."},
                  {"role":"user","content":[{"type":"text","text":"Part one. "},
                                            {"type":"text","text":"Part two."}]}
                ]}""");

        assertNull(inbound.model());
        assertFalse(inbound.stream());
        assertEquals("system", inbound.priorTurns().get(0).role());
        assertEquals("Part one. Part two.", inbound.userMessage());
    }

    @Test
    void parseRejectsMalformedJson() {
        var error = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("not json {{{"));
        assertEquals(400, error.status());
        assertEquals(OpenAiError.TYPE_INVALID_REQUEST, error.type());
    }

    @Test
    void parseRejectsMissingOrEmptyMessages() {
        assertEquals(400, assertThrows(OpenAiError.class,
                () -> OpenAiWire.parse("{\"model\":\"m\"}")).status());
        assertEquals(400, assertThrows(OpenAiError.class,
                () -> OpenAiWire.parse("{\"messages\":[]}")).status());
    }

    @Test
    void parseRequiresFinalUserMessage() {
        var error = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"messages":[{"role":"user","content":"Hi"},
                             {"role":"assistant","content":"Hello"}]}"""));
        assertEquals(400, error.status());
        assertEquals("messages", error.param());
    }

    @Test
    void parseRejectsToolsFunctionsAndToolRoleAsUnsupported() {
        var tools = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"messages":[{"role":"user","content":"Hi"}],
                 "tools":[{"type":"function","function":{"name":"f"}}]}"""));
        assertEquals(400, tools.status());
        assertEquals("unsupported_parameter", tools.code());
        assertEquals("tools", tools.param());

        var functions = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"messages":[{"role":"user","content":"Hi"}],
                 "functions":[{"name":"f"}]}"""));
        assertEquals("unsupported_parameter", functions.code());

        var toolRole = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"messages":[{"role":"tool","content":"result","tool_call_id":"x"},
                             {"role":"user","content":"Hi"}]}"""));
        assertEquals("unsupported_parameter", toolRole.code());
    }

    @Test
    void parseRejectsMultipleChoicesAndNonTextResponseFormat() {
        var n = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"n":2,"messages":[{"role":"user","content":"Hi"}]}"""));
        assertEquals("n", n.param());

        var format = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"response_format":{"type":"json_object"},
                 "messages":[{"role":"user","content":"Hi"}]}"""));
        assertEquals("response_format", format.param());

        // Explicit text response_format stays accepted.
        var accepted = OpenAiWire.parse("""
                {"response_format":{"type":"text"},
                 "messages":[{"role":"user","content":"Hi"}]}""");
        assertEquals("Hi", accepted.userMessage());
    }

    @Test
    void parseRejectsNonBooleanStreamAndNonTextContentParts() {
        var stream = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"stream":"yes","messages":[{"role":"user","content":"Hi"}]}"""));
        assertEquals("stream", stream.param());

        var image = assertThrows(OpenAiError.class, () -> OpenAiWire.parse("""
                {"messages":[{"role":"user","content":[
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,x"}}]}]}"""));
        assertEquals("unsupported_parameter", image.code());
    }

    @Test
    void completionEnvelopeCarriesChoiceAndUsage() {
        var json = OpenAiWire.completionJson("chatcmpl-1", 1234L, "demo",
                "Hello world", TokenUsage.of(3, 5));
        var node = MAPPER.readTree(json);

        assertEquals("chatcmpl-1", node.get("id").stringValue());
        assertEquals("chat.completion", node.get("object").stringValue());
        assertEquals(1234L, node.get("created").asLong());
        assertEquals("demo", node.get("model").stringValue());
        var choice = node.get("choices").get(0);
        assertEquals(0, choice.get("index").asInt());
        assertEquals("assistant", choice.get("message").get("role").stringValue());
        assertEquals("Hello world", choice.get("message").get("content").stringValue());
        assertEquals("stop", choice.get("finish_reason").stringValue());
        assertEquals(3L, node.get("usage").get("prompt_tokens").asLong());
        assertEquals(5L, node.get("usage").get("completion_tokens").asLong());
        assertEquals(8L, node.get("usage").get("total_tokens").asLong());
    }

    @Test
    void completionEnvelopeOmitsUnknownUsage() {
        var json = OpenAiWire.completionJson("chatcmpl-1", 1L, "demo", "Hi", null);
        assertFalse(MAPPER.readTree(json).has("usage"));
    }

    @Test
    void chunkEnvelopesFrameDeltaFinishAndUsage() {
        var content = MAPPER.readTree(OpenAiWire.chunkJson(
                "chatcmpl-1", 7L, "demo", Map.of("content", "Hel"), null));
        assertEquals("chat.completion.chunk", content.get("object").stringValue());
        assertEquals("Hel", content.get("choices").get(0).get("delta")
                .get("content").stringValue());
        assertTrue(content.get("choices").get(0).get("finish_reason").isNull());

        var finish = MAPPER.readTree(OpenAiWire.chunkJson(
                "chatcmpl-1", 7L, "demo", Map.of(), "stop"));
        assertEquals("stop", finish.get("choices").get(0).get("finish_reason").stringValue());
        assertTrue(finish.get("choices").get(0).get("delta").isEmpty());

        var usage = MAPPER.readTree(OpenAiWire.usageChunkJson(
                "chatcmpl-1", 7L, "demo", TokenUsage.of(2, 4)));
        assertTrue(usage.get("choices").isEmpty());
        assertEquals(6L, usage.get("usage").get("total_tokens").asLong());
    }

    @Test
    void errorEnvelopeMatchesOpenAiShape() {
        var node = MAPPER.readTree(OpenAiWire.errorJson(OpenAiError.modelNotFound("nope")));
        var error = node.get("error");
        assertEquals(OpenAiError.TYPE_INVALID_REQUEST, error.get("type").stringValue());
        assertEquals("model_not_found", error.get("code").stringValue());
        assertEquals("model", error.get("param").stringValue());
        assertTrue(error.get("message").stringValue().contains("nope"));
    }

    @Test
    void modelsEnvelopeListsIds() {
        var node = MAPPER.readTree(OpenAiWire.modelsJson(List.of("a", "b"), 9L));
        assertEquals("list", node.get("object").stringValue());
        assertEquals(2, node.get("data").size());
        assertEquals("a", node.get("data").get(0).get("id").stringValue());
        assertEquals("model", node.get("data").get(0).get("object").stringValue());
    }
}
