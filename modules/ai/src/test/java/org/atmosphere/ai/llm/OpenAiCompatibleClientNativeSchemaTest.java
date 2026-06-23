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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level wire assertions for the Built-in {@link OpenAiCompatibleClient}
 * provider-native structured-output path: when a {@link ChatCompletionRequest}
 * carries a {@code jsonSchema}, the client must emit the strict
 * {@code response_format:{type:"json_schema",strict:true,...}} form; when it does
 * not, the legacy {@code json_object} shape (or no response_format) must be
 * preserved exactly. Complements the cross-runtime capability assertion in the
 * contract test (which proves the runtime advertises NATIVE_STRUCTURED_OUTPUT)
 * by proving the schema actually reaches the wire.
 */
class OpenAiCompatibleClientNativeSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String invokeBuildRequestBody(ChatCompletionRequest request) throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test")
                .build();
        Method m = OpenAiCompatibleClient.class.getDeclaredMethod(
                "buildRequestBody", ChatCompletionRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(client, request);
    }

    @Test
    void jsonSchemaEmitsStrictJsonSchemaResponseFormat() throws Exception {
        var schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},"
                + "\"required\":[\"name\"],\"additionalProperties\":false}";
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .user("make a person")
                .jsonMode(true)
                .jsonSchema(schema)
                .build();

        var json = MAPPER.readTree(invokeBuildRequestBody(request));
        var rf = json.get("response_format");
        assertEquals("json_schema", rf.get("type").stringValue(),
                "a request carrying a jsonSchema must upgrade to json_schema, got: " + json);
        var js = rf.get("json_schema");
        assertTrue(js.get("strict").booleanValue(), "strict mode must be enabled");
        assertEquals("structured_output", js.get("name").stringValue());
        // The schema must be embedded as a nested JSON object, not a string.
        assertTrue(js.get("schema").isObject(), "schema must be embedded as an object");
        assertEquals(false, js.get("schema").get("additionalProperties").booleanValue());
    }

    @Test
    void jsonModeWithoutSchemaKeepsJsonObject() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .user("make a person")
                .jsonMode(true)
                .build();

        var json = MAPPER.readTree(invokeBuildRequestBody(request));
        assertEquals("json_object", json.get("response_format").get("type").stringValue(),
                "without a jsonSchema the legacy json_object shape must be preserved: " + json);
    }

    @Test
    void noStructuredOutputEmitsNoResponseFormat() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .user("hello")
                .build();

        var json = MAPPER.readTree(invokeBuildRequestBody(request));
        assertFalse(json.has("response_format"),
                "a plain request must not emit response_format: " + json);
    }

    @Test
    void blankSchemaFallsBackToJsonObject() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .user("make a person")
                .jsonMode(true)
                .jsonSchema("   ")
                .build();

        var json = MAPPER.readTree(invokeBuildRequestBody(request));
        assertEquals("json_object", json.get("response_format").get("type").stringValue(),
                "a blank schema must not produce a malformed json_schema block: " + json);
    }
}
