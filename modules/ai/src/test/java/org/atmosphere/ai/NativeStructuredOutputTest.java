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
package org.atmosphere.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the provider-native structured-output foundation: the strict-mode-valid
 * raw schema generator, the tri-state {@link NativeStructuredOutputMode}, the
 * schema-rejection heuristic, and — through its production consumer
 * {@link AiPipeline} — the {@link NativeStructuredOutputMode#AUTO} graceful
 * fall-back to the prompt-injection path when a provider rejects the schema.
 */
class NativeStructuredOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Address(String city, String zip) { }

    public enum Role { ADMIN, USER }

    public record Person(String name, int age, Address address, List<String> tags, Role role) { }

    public record Node(String label, Node child) { }

    // -- Strict-mode-valid raw schema ----------------------------------------

    @Test
    void rawSchemaIsStrictModeValid() {
        var schema = new JacksonStructuredOutputParser().jsonSchema(Person.class);
        assertNotNull(schema, "a record must produce a machine-readable schema");
        JsonNode root = MAPPER.readTree(schema);

        assertEquals("object", root.get("type").stringValue());
        // Strict mode requires closed objects and a complete required list.
        assertFalse(root.get("additionalProperties").booleanValue(),
                "objects must be closed for strict mode: " + schema);
        var required = root.get("required");
        assertEquals(5, required.size(), "every property must be required for strict mode");

        // Nested record is recursed (not a bare {type:object}).
        var address = root.get("properties").get("address");
        assertEquals("object", address.get("type").stringValue());
        assertFalse(address.get("additionalProperties").booleanValue());
        assertTrue(address.get("properties").has("city"),
                "nested record must be expanded: " + schema);

        // List carries an items schema.
        var tags = root.get("properties").get("tags");
        assertEquals("array", tags.get("type").stringValue());
        assertEquals("string", tags.get("items").get("type").stringValue());

        // Enum becomes a string with an enum constraint.
        var role = root.get("properties").get("role");
        assertEquals("string", role.get("type").stringValue());
        assertEquals(2, role.get("enum").size());
    }

    @Test
    void recursiveTypeTerminatesWithOpenObject() {
        // A self-referential record must not loop forever; the cycle is cut with
        // an open object (which the AUTO graceful fall-back covers if a provider
        // rejects it).
        var schema = new JacksonStructuredOutputParser().jsonSchema(Node.class);
        assertNotNull(schema);
        JsonNode root = MAPPER.readTree(schema);
        var child = root.get("properties").get("child");
        assertEquals("object", child.get("type").stringValue());
        assertTrue(child.get("additionalProperties").booleanValue(),
                "a recursive cycle must be cut with an open object: " + schema);
    }

    @Test
    void voidTypeHasNoSchema() {
        assertNull(new JacksonStructuredOutputParser().jsonSchema(Void.class));
    }

    // -- Tri-state mode parse ------------------------------------------------

    @Test
    void modeParseIsLenientAndDefaultsToAuto() {
        assertEquals(NativeStructuredOutputMode.AUTO, NativeStructuredOutputMode.parse(null));
        assertEquals(NativeStructuredOutputMode.AUTO, NativeStructuredOutputMode.parse("  "));
        assertEquals(NativeStructuredOutputMode.AUTO, NativeStructuredOutputMode.parse("nonsense"));
        assertEquals(NativeStructuredOutputMode.ENABLED, NativeStructuredOutputMode.parse("ENABLED"));
        assertEquals(NativeStructuredOutputMode.ENABLED, NativeStructuredOutputMode.parse(" on "));
        assertEquals(NativeStructuredOutputMode.DISABLED, NativeStructuredOutputMode.parse("disabled"));
        assertEquals(NativeStructuredOutputMode.DISABLED, NativeStructuredOutputMode.parse("off"));
    }

    // -- Schema-rejection heuristic ------------------------------------------

    @Test
    void schemaRejectionHeuristicMatchesProviderRejections() {
        assertTrue(NativeStructuredOutput.isSchemaRejection(
                new RuntimeException("API returned 400: Invalid schema for response_format")));
        assertTrue(NativeStructuredOutput.isSchemaRejection(
                new RuntimeException("json_schema is not supported by this model")));
        // Walks the cause chain.
        assertTrue(NativeStructuredOutput.isSchemaRejection(
                new RuntimeException("wrapper", new IllegalStateException("unsupported schema"))));
    }

    @Test
    void schemaRejectionHeuristicIgnoresUnrelatedFailures() {
        assertFalse(NativeStructuredOutput.isSchemaRejection(
                new RuntimeException("API returned 429: rate limit exceeded")));
        assertFalse(NativeStructuredOutput.isSchemaRejection(
                new RuntimeException("Connection reset")));
        assertFalse(NativeStructuredOutput.isSchemaRejection(new RuntimeException((String) null)));
    }

    // -- AUTO graceful fall-back through the pipeline ------------------------

    @Test
    void autoFallsBackToPromptInjectionOnSchemaRejection() {
        var nativeAttempts = new AtomicInteger();
        var fallbackAttempts = new AtomicInteger();
        // First dispatch (native applied) rejects the schema pre-stream; the
        // pipeline must re-dispatch WITHOUT native and the valid output must ship.
        AgentRuntime runtime = new NativeCapableRuntime(ctx -> {
            if (NativeStructuredOutput.shouldApply(ctx)) {
                nativeAttempts.incrementAndGet();
                throw new SchemaRejected();
            }
            fallbackAttempts.incrementAndGet();
            return "{\"name\":\"Alice\",\"age\":30}";
        });
        var session = new CollectingSession("native-fallback");

        pipeline(runtime, SimplePerson.class).execute("c1", "make a person", session, Map.of());

        assertTrue(session.await(Duration.ofSeconds(3)));
        assertFalse(session.failed(), "graceful fall-back must succeed: " + session.failure());
        assertEquals(1, nativeAttempts.get(), "native attempt must run once");
        assertEquals(1, fallbackAttempts.get(), "fall-back must re-dispatch exactly once");
        assertTrue(session.text().contains("Alice"),
                "the fall-back attempt's output must reach the client: " + session.text());
    }

    @Test
    void nativeHappyPathStreamsWithoutFallback() {
        var nativeAttempts = new AtomicInteger();
        AgentRuntime runtime = new NativeCapableRuntime(ctx -> {
            // The pipeline must opt in to native for a capable runtime.
            assertTrue(NativeStructuredOutput.shouldApply(ctx),
                    "pipeline must apply native for a NATIVE_STRUCTURED_OUTPUT runtime");
            assertNotNull(NativeStructuredOutput.schema(ctx), "schema must be threaded");
            nativeAttempts.incrementAndGet();
            return "{\"name\":\"Bob\",\"age\":42}";
        });
        var session = new CollectingSession("native-ok");

        pipeline(runtime, SimplePerson.class).execute("c1", "make a person", session, Map.of());

        assertTrue(session.await(Duration.ofSeconds(3)));
        assertFalse(session.failed(), "native happy path must succeed: " + session.failure());
        assertEquals(1, nativeAttempts.get(), "exactly one dispatch on the happy path");
        assertTrue(session.text().contains("Bob"));
    }

    public record SimplePerson(String name, int age) { }

    private AiPipeline pipeline(AgentRuntime runtime, Class<?> responseType) {
        return new AiPipeline(runtime, "system", "test-model",
                null, null, List.of(), List.of(), null, responseType);
    }

    /** Rejection whose message trips {@link NativeStructuredOutput#isSchemaRejection}. */
    private static final class SchemaRejected extends RuntimeException {
        SchemaRejected() {
            super("API returned 400: Invalid schema for response_format 'structured_output'");
        }
    }

    /** Runtime that advertises NATIVE_STRUCTURED_OUTPUT and is scripted per call. */
    private static final class NativeCapableRuntime implements AgentRuntime {
        private final java.util.function.Function<AgentExecutionContext, String> script;

        NativeCapableRuntime(java.util.function.Function<AgentExecutionContext, String> script) {
            this.script = script;
        }

        @Override
        public String name() {
            return "native-capable";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.STRUCTURED_OUTPUT,
                    AiCapability.NATIVE_STRUCTURED_OUTPUT, AiCapability.SYSTEM_PROMPT);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            final String reply;
            try {
                reply = script.apply(context);
            } catch (RuntimeException e) {
                session.error(e);
                return;
            }
            session.send(reply);
            session.complete();
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
            // no-op test stub
        }
    }
}
