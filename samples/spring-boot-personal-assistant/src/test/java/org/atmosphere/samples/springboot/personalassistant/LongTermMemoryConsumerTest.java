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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.memory.MemoryExtractionStrategy;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the personal-assistant sample is a real {@link LongTermMemory}
 * consumer: a fact extracted when one "session" closes is recalled and
 * injected into the system prompt of a later session for the same user.
 *
 * <p>The test drives the exact production wire — the no-arg
 * {@link PersonalAssistantMemoryInterceptor} that the
 * {@code @AiEndpoint(interceptors=...)} scanner instantiates, delegating
 * through {@link LongTermMemoryHolder} to the framework
 * {@link LongTermMemoryInterceptor} and a real
 * {@link InMemoryLongTermMemory} — exactly the objects
 * {@link LongTermMemoryConfig} builds at startup. Only the extraction
 * {@link AgentRuntime} is a deterministic stub so the test needs no LLM key.</p>
 */
class LongTermMemoryConsumerTest {

    private static final String USER = "alice";

    @AfterEach
    void tearDown() {
        LongTermMemoryHolder.clear();
    }

    /**
     * Stub runtime that returns a fixed JSON fact array on extraction, so the
     * {@code onSessionClose} strategy stores deterministic facts without a
     * live model. {@code AgentRuntime} has internal/sealed-adjacent default
     * methods; a dynamic proxy implements only the handful the extraction
     * path touches and lets the interface defaults cover the rest.
     */
    private static AgentRuntime stubRuntime(String factsJson) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "name" -> "stub";
            case "isAvailable" -> Boolean.TRUE;
            case "priority" -> 0;
            case "configure" -> null;
            case "execute" -> {
                // OnSessionCloseStrategy.extractFacts() calls execute(context, session);
                // emit the JSON array then complete so the latch releases.
                var session = (StreamingSession) args[1];
                session.send(factsJson);
                session.complete();
                yield null;
            }
            case "toString" -> "stubRuntime";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> {
                if (method.isDefault()) {
                    yield InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException("stub does not implement " + method.getName());
            }
        };
        return (AgentRuntime) Proxy.newProxyInstance(
                AgentRuntime.class.getClassLoader(),
                new Class<?>[] {AgentRuntime.class},
                handler);
    }

    /**
     * Minimal {@link AtmosphereResource} proxy — {@code preProcess} only reads
     * identity off the {@link AiRequest}, so the resource is never touched on
     * the recall path; a no-op proxy is sufficient and avoids a Mockito mock
     * of the (sealed-adjacent) resource type.
     */
    private static AtmosphereResource noopResource() {
        return (AtmosphereResource) Proxy.newProxyInstance(
                AtmosphereResource.class.getClassLoader(),
                new Class<?>[] {AtmosphereResource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "uuid" -> "test-conv";
                    case "toString" -> "noopResource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> {
                        var rt = method.getReturnType();
                        if (rt == boolean.class) {
                            yield Boolean.FALSE;
                        }
                        yield null;
                    }
                });
    }

    /**
     * Build the real interceptor + backend the way {@link LongTermMemoryConfig}
     * does, but with the stub extraction runtime, and publish into the holder
     * so {@link PersonalAssistantMemoryInterceptor} (the registered no-arg
     * interceptor) delegates through it.
     */
    private LongTermMemory wireMemory(AgentRuntime runtime) {
        var memory = new InMemoryLongTermMemory(20);
        var delegate = new LongTermMemoryInterceptor(
                memory, MemoryExtractionStrategy.onSessionClose(), runtime, 20);
        LongTermMemoryHolder.set(delegate, memory);
        return memory;
    }

    @Test
    void factExtractedAtSessionCloseIsRecalledInLaterSession() {
        var memory = wireMemory(stubRuntime("[\"Has a golden retriever named Max\"]"));
        var registered = new PersonalAssistantMemoryInterceptor();
        var resource = noopResource();

        // --- Session 1: user mentions their dog; session closes -> extract+store.
        var session1History = List.of(
                ChatMessage.user("My dog Max is a golden retriever"),
                ChatMessage.assistant("Noted — Max sounds lovely!"));
        registered.onDisconnect(USER, "conv-session-1", session1History);

        assertTrue(memory.factCount(USER) > 0,
                "session-close extraction should have stored at least one fact for " + USER);
        assertTrue(memory.getFacts(USER, 20).contains("Has a golden retriever named Max"),
                "the extracted dog fact should be persisted in long-term memory");

        // --- Session 2 (new connection, same user): recall injects the fact.
        var session2Request = new AiRequest("What pet do I have?", "You are a helpful assistant.")
                .withUserId(USER)
                .withConversationId("conv-session-2");
        var recalled = registered.preProcess(session2Request, resource);

        assertTrue(recalled.systemPrompt().contains("Has a golden retriever named Max"),
                "the fact stored in session 1 must be recalled into session 2's system prompt; "
                        + "actual prompt: " + recalled.systemPrompt());
        assertTrue(recalled.systemPrompt().contains("Known facts about this user"),
                "recall should add the known-facts block to the system prompt");
        // The user's actual message must be untouched by recall.
        assertTrue(recalled.message().equals("What pet do I have?"),
                "preProcess must not mutate the user message");
    }

    @Test
    void factsDoNotLeakAcrossUsers() {
        var memory = wireMemory(stubRuntime("[\"Lives in Montreal\"]"));
        var registered = new PersonalAssistantMemoryInterceptor();
        var resource = noopResource();

        registered.onDisconnect(USER, "conv-a",
                List.of(ChatMessage.user("I live in Montreal")));
        assertTrue(memory.factCount(USER) > 0, "alice should have a stored fact");

        // A different user with no stored facts gets no known-facts block.
        var bobRequest = new AiRequest("Where do I live?", "You are a helpful assistant.")
                .withUserId("bob")
                .withConversationId("conv-b");
        var recalled = registered.preProcess(bobRequest, resource);

        assertFalse(recalled.systemPrompt().contains("Lives in Montreal"),
                "alice's fact must not appear in bob's recalled prompt");
        assertTrue(recalled.systemPrompt().equals("You are a helpful assistant."),
                "with no stored facts the system prompt is returned unchanged");
    }

    @Test
    void emptyHolderFallsThroughUntouched() {
        // Holder never set (or cleared) -> the no-arg interceptor must be a no-op.
        LongTermMemoryHolder.clear();
        var registered = new PersonalAssistantMemoryInterceptor();
        var resource = noopResource();

        var request = new AiRequest("hi", "base prompt").withUserId(USER);
        var result = registered.preProcess(request, resource);

        assertTrue(result.systemPrompt().equals("base prompt"),
                "with no backend wired, preProcess returns the request unchanged");
        // postProcess / onDisconnect must not throw with an empty holder.
        registered.postProcess(request, resource);
        registered.onDisconnect(USER, "conv", List.of(ChatMessage.user("x")));
        assertNull(LongTermMemoryHolder.memory(), "holder remains empty");
    }
}
