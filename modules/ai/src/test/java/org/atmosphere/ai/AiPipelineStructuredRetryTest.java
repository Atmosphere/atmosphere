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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the self-healing structured-output reprompt loop (P0.2) through its
 * production consumer {@link AiPipeline}: retry-then-succeed, exhaust-then-fail-
 * closed, disabled-is-single-shot, and the reprompt-carries-the-error contract.
 */
class AiPipelineStructuredRetryTest {

    public record Person(String name, int age) { }

    private AiPipeline pipeline(AgentRuntime runtime, Class<?> responseType) {
        return new AiPipeline(runtime, "system", "test-model",
                null, null, List.of(), List.of(), null, responseType);
    }

    @Test
    void rePromptsAndSucceedsOnSecondAttempt() {
        var calls = new AtomicInteger();
        AgentRuntime runtime = new ScriptedRuntime(ctx -> calls.incrementAndGet() == 1
                ? "this is not json at all"
                : "{\"name\":\"Alice\",\"age\":30}");
        var session = new CollectingSession("retry-ok");

        pipeline(runtime, Person.class).execute("c1", "make a person", session,
                Map.of(AiStructuredRetry.METADATA_KEY, AiStructuredRetry.of(2)));

        assertTrue(session.await(Duration.ofSeconds(3)));
        assertFalse(session.failed(), "valid second attempt must succeed: " + session.failure());
        assertEquals(2, calls.get(), "runtime must be re-invoked exactly once after the bad attempt");
        assertTrue(session.text().contains("Alice"),
                "the successful attempt's text must be replayed downstream: " + session.text());
    }

    @Test
    void failsClosedWhenAllAttemptsInvalid() {
        var calls = new AtomicInteger();
        AgentRuntime runtime = new ScriptedRuntime(ctx -> {
            calls.incrementAndGet();
            return "still not json";
        });
        var session = new CollectingSession("retry-exhaust");

        pipeline(runtime, Person.class).execute("c1", "make a person", session,
                Map.of(AiStructuredRetry.METADATA_KEY, AiStructuredRetry.of(2)));

        assertTrue(session.await(Duration.ofSeconds(3)));
        assertTrue(session.failed(), "exhausted retries must fail closed, not silently succeed");
        assertInstanceOf(StructuredOutputParser.StructuredOutputException.class, session.failure());
        assertEquals(3, calls.get(), "1 initial + 2 retries = 3 attempts");
    }

    @Test
    void disabledRetryIsSingleShot() {
        var calls = new AtomicInteger();
        AgentRuntime runtime = new ScriptedRuntime(ctx -> {
            calls.incrementAndGet();
            return "not json";
        });
        var session = new CollectingSession("retry-off");

        // No AiStructuredRetry in metadata → unchanged legacy behavior: the
        // capturing session logs the parse failure and completes without error.
        pipeline(runtime, Person.class).execute("c1", "make a person", session);

        assertTrue(session.await(Duration.ofSeconds(3)));
        assertEquals(1, calls.get(), "retry disabled must not re-invoke the runtime");
        assertFalse(session.failed(),
                "legacy single-shot path completes (does not fail closed) when retry is off");
    }

    @Test
    void succeedsFirstAttemptWithoutExtraCalls() {
        var calls = new AtomicInteger();
        AgentRuntime runtime = new ScriptedRuntime(ctx -> {
            calls.incrementAndGet();
            return "{\"name\":\"Bob\",\"age\":42}";
        });
        var session = new CollectingSession("retry-first");

        pipeline(runtime, Person.class).execute("c1", "make a person", session,
                Map.of(AiStructuredRetry.METADATA_KEY, AiStructuredRetry.of(3)));

        assertTrue(session.await(Duration.ofSeconds(3)));
        assertFalse(session.failed());
        assertEquals(1, calls.get(), "a valid first attempt must not trigger any reprompt");
        assertTrue(session.text().contains("Bob"));
    }

    @Test
    void repromptCarriesValidationErrorAndBadOutput() {
        var secondMessage = new AtomicReference<String>();
        var calls = new AtomicInteger();
        AgentRuntime runtime = new ScriptedRuntime(ctx -> {
            if (calls.incrementAndGet() == 2) {
                secondMessage.set(ctx.message());
                return "{\"name\":\"Carol\",\"age\":7}";
            }
            return "GARBAGE-OUTPUT-XYZ";
        });
        var session = new CollectingSession("retry-feedback");

        pipeline(runtime, Person.class).execute("c1", "make a person", session,
                Map.of(AiStructuredRetry.METADATA_KEY, AiStructuredRetry.of(1)));

        assertTrue(session.await(Duration.ofSeconds(3)));
        var reprompt = secondMessage.get();
        assertTrue(reprompt != null && reprompt.contains("did not match"),
                "reprompt must tell the model its output failed validation: " + reprompt);
        assertTrue(reprompt.contains("GARBAGE-OUTPUT-XYZ"),
                "reprompt must echo the prior invalid output so the model can correct it");
    }

    /** Runtime whose reply is computed per call by a scripted function. */
    private static final class ScriptedRuntime implements AgentRuntime {
        private final java.util.function.Function<AgentExecutionContext, String> script;

        ScriptedRuntime(java.util.function.Function<AgentExecutionContext, String> script) {
            this.script = script;
        }

        @Override
        public String name() {
            return "scripted";
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
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send(script.apply(context));
            session.complete();
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
            // no-op test stub
        }
    }
}
