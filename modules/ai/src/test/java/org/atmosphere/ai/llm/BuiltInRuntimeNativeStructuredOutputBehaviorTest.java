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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.NativeStructuredOutput;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral proof that {@link BuiltInAgentRuntime} genuinely <em>implements</em>
 * {@link org.atmosphere.ai.AiCapability#NATIVE_STRUCTURED_OUTPUT}, not merely
 * declares it.
 *
 * <p>{@code AbstractAgentRuntimeContractTest} pins {@code capabilities()} against a
 * hand-maintained expected set — that is declaration-equality, and it cannot tell
 * a real provider-native schema enforcement from prompt-injection wearing the same
 * capability flag. This test closes that gap for the Built-in runtime: it drives
 * the full dispatch path ({@code execute} → {@code doExecute} → {@code buildRequest}
 * → {@link OpenAiCompatibleClient} wire serialization) through a capturing
 * {@link LlmClient} and asserts that when the pipeline opts a request into native
 * structured output, the runtime threads the JSON Schema into the OpenAI wire
 * request's {@code response_format:{type:"json_schema",...}} field — and that
 * without the opt-in it stays on the {@code json_object} / prompt-injection path.</p>
 *
 * <p>The blog's "Runtime truth" claim — a runtime cannot advertise a capability the
 * bytecode does not perform — is only true if a test like this exists. It does.</p>
 */
class BuiltInRuntimeNativeStructuredOutputBehaviorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Simple structured-output target; the Jackson parser renders a strict schema. */
    record Person(String name, int age) {
    }

    /**
     * Test double that records the {@link ChatCompletionRequest} the runtime built
     * instead of issuing a network call. The runtime's {@code doExecute} invokes the
     * 2-arg {@link LlmClient#streamChatCompletion} — the same seam production uses.
     */
    static final class CapturingLlmClient implements LlmClient {
        volatile ChatCompletionRequest captured;

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
            this.captured = request;
            // Leave the session in a completed terminal state so execute()'s
            // fireCompletion path runs and the runtime returns cleanly.
            session.complete();
        }
    }

    /** Render a request body via the private wire serializer (same reflection seam as the wire test). */
    private static String wireBody(ChatCompletionRequest request) throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test")
                .build();
        Method m = OpenAiCompatibleClient.class.getDeclaredMethod(
                "buildRequestBody", ChatCompletionRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(client, request);
    }

    private static AgentExecutionContext baseContext() {
        return new AgentExecutionContext(
                "make a person", "system", "gpt-4o-mini",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
    }

    private static ChatCompletionRequest drive(AgentExecutionContext context) {
        var runtime = new BuiltInAgentRuntime();
        var capturing = new CapturingLlmClient();
        // Public, reflection-free injection seam; no production behavior changes.
        runtime.configureNativeClient(capturing);
        runtime.execute(context, new CollectingSession());
        assertNotNull(capturing.captured, "runtime never dispatched a request to the client");
        return capturing.captured;
    }

    @Test
    void nativeOptInThreadsGeneratedSchemaIntoResponseFormatJsonSchema() throws Exception {
        // The pipeline renders the schema for the response type and stamps it onto
        // the context via NativeStructuredOutput.withApply (APPLY + SCHEMA metadata).
        var schema = NativeStructuredOutput.schemaFor(Person.class);
        assertNotNull(schema, "Jackson parser must render a machine-readable schema for the record");

        var context = NativeStructuredOutput.withApply(
                baseContext().withResponseType(Person.class), schema);

        var request = drive(context);

        // 1. The RUNTIME threaded the exact schema the pipeline stamped onto the request.
        assertEquals(schema, request.jsonSchema(),
                "BuiltInAgentRuntime must thread the native schema onto the ChatCompletionRequest");

        // 2. The schema reaches the OpenAI WIRE as response_format:{type:"json_schema",...},
        //    i.e. provider-level enforcement — not a system-prompt instruction.
        var json = MAPPER.readTree(wireBody(request));
        var rf = json.get("response_format");
        assertNotNull(rf, "native structured output must emit a response_format block: " + json);
        assertEquals("json_schema", rf.get("type").stringValue(),
                "must upgrade to json_schema, got: " + json);
        var js = rf.get("json_schema");
        assertTrue(js.get("strict").booleanValue(), "strict mode must be enabled");
        assertTrue(js.get("schema").isObject(), "schema must be embedded as a JSON object, not a string");
        assertEquals(false, js.get("schema").get("additionalProperties").booleanValue(),
                "strict-mode schema must close additionalProperties");
        assertTrue(js.get("schema").get("properties").has("name"),
                "the threaded schema must describe the record's fields: " + json);
    }

    @Test
    void structuredRequestWithoutNativeOptInStaysOnJsonObject() throws Exception {
        // responseType is set (structured output requested) but the pipeline did NOT
        // opt into native enforcement — the runtime must fall back to json_object /
        // prompt-injection and must NOT fabricate a json_schema on the wire.
        var context = baseContext().withResponseType(Person.class);

        var request = drive(context);

        assertNull(request.jsonSchema(),
                "without the native opt-in the runtime must not set a native schema");
        assertTrue(request.jsonMode(),
                "structured output still rides json_object when native is off");

        var json = MAPPER.readTree(wireBody(request));
        assertEquals("json_object", json.get("response_format").get("type").stringValue(),
                "native json_schema must be gated on the opt-in, not emitted unconditionally: " + json);
    }

    @Test
    void plainRequestEmitsNoResponseFormat() throws Exception {
        var request = drive(baseContext());

        assertNull(request.jsonSchema());
        assertFalse(request.jsonMode());

        var json = MAPPER.readTree(wireBody(request));
        assertFalse(json.has("response_format"),
                "a plain non-structured request must not emit response_format: " + json);
    }
}
