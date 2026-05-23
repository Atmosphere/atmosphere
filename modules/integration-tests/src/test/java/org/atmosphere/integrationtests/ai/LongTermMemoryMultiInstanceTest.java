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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.memory.MemoryExtractionStrategy;
import org.atmosphere.session.redis.RedisLongTermMemory;
import org.atmosphere.session.sqlite.SqliteLongTermMemory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-instance test: two {@link LongTermMemoryInterceptor} instances
 * backed by two <em>separate</em> {@link LongTermMemory} handles pointing
 * at the same underlying store (same SQLite file, same Redis container).
 * This is the pod-A-writes / pod-B-reads scenario the persistent backends
 * exist for, distinct from the single-handle composition test in
 * {@link LongTermMemoryBackendIntegrationTest} where both interceptor
 * instances shared one in-process backend.
 *
 * <p>The {@code InMemoryLongTermMemory} variant is intentionally excluded
 * — its facts cannot be shared across instances, so the test would be
 * vacuous.</p>
 */
@Tag("ai")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LongTermMemoryMultiInstanceTest {

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private static GenericContainer<?> redis;
    private static String redisUri;
    private static Path sqliteDb;

    @BeforeAll
    public void setUp() throws Exception {
        sqliteDb = Files.createTempFile("ltm-multi-instance-", ".db");
        Files.deleteIfExists(sqliteDb);
        if (DOCKER_AVAILABLE) {
            redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redis.start();
            redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        }
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (redis != null) {
            redis.stop();
        }
        if (sqliteDb != null) {
            Files.deleteIfExists(sqliteDb);
        }
    }

    static Stream<Arguments> persistentBackends() {
        return Stream.of(
                Arguments.of("Sqlite",
                        (Supplier<LongTermMemory>) () ->
                                new SqliteLongTermMemory(sqliteDb, 100),
                        true),
                Arguments.of("Redis",
                        (Supplier<LongTermMemory>) () ->
                                new RedisLongTermMemory(redisUri),
                        false));
    }

    /**
     * Pod A and Pod B each own a {@code LongTermMemoryInterceptor}, each
     * pointing at its own fresh {@link LongTermMemory} handle backed by
     * the same underlying store. Pod A handles a user session,
     * {@code onDisconnect} extracts facts and persists them. Pod B then
     * handles a follow-up session for the same user and must inject the
     * persisted facts via {@code preProcess}, proving the cross-instance
     * read path.
     */
    @ParameterizedTest(name = "{0} backend: pod A writes, pod B reads")
    @MethodSource("persistentBackends")
    void podAWritesPodBReads(String label,
                             Supplier<LongTermMemory> backendFactory,
                             boolean alwaysAvailable) {
        if (!alwaysAvailable) {
            Assumptions.assumeTrue(DOCKER_AVAILABLE,
                    "Docker not available — skipping " + label);
        }

        var userId = "user-multi-" + label.toLowerCase();

        // ---- Pod A: write side ----
        var backendA = backendFactory.get();
        backendA.clear(userId);
        var extractedFacts = List.of(
                "Speaks French as a first language",
                "Prefers JetBrains IDEs");
        var interceptorA = new LongTermMemoryInterceptor(
                backendA, new StaticFactsStrategy(extractedFacts),
                (AgentRuntime) null, 10);

        var history = List.of(
                ChatMessage.user("Salut, je code en Kotlin sur IntelliJ"),
                ChatMessage.assistant("Cool — French speaker, JetBrains user"));
        interceptorA.onDisconnect(userId, "conv-pod-a", history);
        closeQuietly(backendA);
        // Pod A is gone. Its in-process state is reclaimed; only the
        // persisted store survives.

        // ---- Pod B: read side ----
        var backendB = backendFactory.get();
        var interceptorB = new LongTermMemoryInterceptor(
                backendB, new StaticFactsStrategy(List.of()),
                (AgentRuntime) null, 10);

        var augmented = interceptorB.preProcess(
                new AiRequest("any IDE tips?", "You are a coding assistant")
                        .withUserId(userId),
                null);

        assertNotNull(augmented.systemPrompt());
        assertTrue(augmented.systemPrompt().contains("Speaks French as a first language"),
                label + ": pod B should read fact persisted by pod A");
        assertTrue(augmented.systemPrompt().contains("Prefers JetBrains IDEs"),
                label + ": pod B should read both persisted facts");
        assertFalse(augmented.systemPrompt().equals("You are a coding assistant"),
                label + ": system prompt should be augmented with pod-A's known-facts block");

        backendB.clear(userId);
        closeQuietly(backendB);
    }

    /**
     * Bidirectional case: both pods accumulate facts on their own session,
     * each one's onDisconnect persists, and a third reader sees the union.
     * Mirrors a load-balanced deployment where conversations for the same
     * user land on different pods turn after turn.
     */
    @ParameterizedTest(name = "{0} backend: facts from two pods both visible to reader")
    @MethodSource("persistentBackends")
    void bidirectionalAccumulation(String label,
                                   Supplier<LongTermMemory> backendFactory,
                                   boolean alwaysAvailable) {
        if (!alwaysAvailable) {
            Assumptions.assumeTrue(DOCKER_AVAILABLE,
                    "Docker not available — skipping " + label);
        }
        var userId = "user-bidir-" + label.toLowerCase();

        // First pod
        var podA = backendFactory.get();
        podA.clear(userId);
        new LongTermMemoryInterceptor(podA,
                new StaticFactsStrategy(List.of("Pod-A-derived fact")),
                (AgentRuntime) null, 10)
                .onDisconnect(userId, "conv-1",
                        List.of(ChatMessage.user("x"), ChatMessage.assistant("y")));
        closeQuietly(podA);

        // Second pod — different handle, same backend
        var podB = backendFactory.get();
        new LongTermMemoryInterceptor(podB,
                new StaticFactsStrategy(List.of("Pod-B-derived fact")),
                (AgentRuntime) null, 10)
                .onDisconnect(userId, "conv-2",
                        List.of(ChatMessage.user("u"), ChatMessage.assistant("v")));
        closeQuietly(podB);

        // Third pod reads
        var podC = backendFactory.get();
        var augmented = new LongTermMemoryInterceptor(podC,
                new StaticFactsStrategy(List.of()),
                (AgentRuntime) null, 10)
                .preProcess(new AiRequest("status?", "system").withUserId(userId), null);

        assertTrue(augmented.systemPrompt().contains("Pod-A-derived fact"),
                label + ": reader should see pod-A's fact");
        assertTrue(augmented.systemPrompt().contains("Pod-B-derived fact"),
                label + ": reader should see pod-B's fact");

        podC.clear(userId);
        closeQuietly(podC);
    }

    private static void closeQuietly(LongTermMemory backend) {
        if (backend instanceof SqliteLongTermMemory s) {
            s.close();
        } else if (backend instanceof RedisLongTermMemory r) {
            r.close();
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    private static final class StaticFactsStrategy implements MemoryExtractionStrategy {
        private final List<String> facts;

        StaticFactsStrategy(List<String> facts) {
            this.facts = List.copyOf(facts);
        }

        @Override
        public boolean shouldExtract(String conversationId, String message, int messageCount) {
            return false;
        }

        @Override
        public List<String> extractFacts(String conversationText, AgentRuntime runtime) {
            return facts;
        }
    }
}
