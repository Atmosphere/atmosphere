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
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition test: drives {@link LongTermMemoryInterceptor} against every
 * {@link LongTermMemory} backend that ships in-tree
 * ({@link InMemoryLongTermMemory}, {@link SqliteLongTermMemory},
 * {@link RedisLongTermMemory}) and proves the full lifecycle:
 *
 * <ul>
 *   <li>{@code preProcess} injects stored facts into the system prompt;</li>
 *   <li>{@code onDisconnect} extracts facts from the conversation history and
 *       persists them through the backend;</li>
 *   <li>a fresh {@link LongTermMemoryInterceptor} instance pointing at the
 *       same backend reads the persisted facts back — the multi-instance
 *       ("pod A writes, pod B reads") scenario that's the whole reason the
 *       persistent backends exist.</li>
 * </ul>
 *
 * <p>The Redis variant uses Testcontainers ({@code redis:7-alpine}) and is
 * skipped when Docker is unavailable. The SQLite variant uses a file-backed
 * database; the in-memory variant exercises the same composition without
 * persistence guarantees, as a baseline.</p>
 */
@Tag("ai")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LongTermMemoryBackendIntegrationTest {

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private static GenericContainer<?> redis;
    private static String redisUri;
    private static Path sqliteDb;

    @BeforeAll
    public void setUp() throws Exception {
        sqliteDb = Files.createTempFile("ltm-integration-", ".db");
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

    static Stream<Arguments> backends() {
        return Stream.of(
                Arguments.of("InMemory",
                        (Supplier<LongTermMemory>) InMemoryLongTermMemory::new,
                        true),
                Arguments.of("Sqlite",
                        (Supplier<LongTermMemory>) () ->
                                new SqliteLongTermMemory(sqliteDb, 100),
                        true),
                Arguments.of("Redis",
                        (Supplier<LongTermMemory>) () ->
                                new RedisLongTermMemory(redisUri),
                        false));
    }

    @ParameterizedTest(name = "{0} backend round-trips facts through Interceptor")
    @MethodSource("backends")
    void interceptorRoundTripsFactsThroughBackend(String label,
                                                  Supplier<LongTermMemory> backendFactory,
                                                  boolean alwaysAvailable) {
        if (!alwaysAvailable) {
            Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker not available — skipping " + label);
        }

        var userId = "user-" + label.toLowerCase();
        // The interceptor's preProcess path never dereferences the
        // AtmosphereResource, and the StaticFactsStrategy ignores its
        // AgentRuntime argument, so both can be null. This keeps the
        // integration-tests module free of a Mockito dependency.
        var runtime = (AgentRuntime) null;

        // ---- Writer side: one LongTermMemoryInterceptor with the backend ----
        var writeBackend = backendFactory.get();
        writeBackend.clear(userId);

        // Stub strategy that pretends an LLM extracted two facts on disconnect.
        var strategy = new StaticFactsStrategy(
                List.of("Has a dog named Max", "Lives in Montreal"));
        var writeInterceptor = new LongTermMemoryInterceptor(
                writeBackend, strategy, runtime, 10);

        // 1. preProcess on a virgin user injects nothing (no facts yet).
        var emptyResult = writeInterceptor.preProcess(
                new AiRequest("hello", "system prompt").withUserId(userId), null);
        assertEquals("system prompt", emptyResult.systemPrompt(),
                label + ": empty backend should not augment system prompt");

        // 2. onDisconnect with a conversation history flushes the extracted
        //    facts through the backend.
        var history = List.of(
                ChatMessage.user("Hi, I'm in Montreal"),
                ChatMessage.assistant("Cool, what brings you here?"),
                ChatMessage.user("My dog Max needed a walk"));
        writeInterceptor.onDisconnect(userId, "conv-1", history);

        // 3. Facts are visible through the same backend instance.
        var facts = writeBackend.getFacts(userId, 10);
        assertEquals(2, facts.size(), label + ": both extracted facts should persist");
        assertTrue(facts.contains("Has a dog named Max"));
        assertTrue(facts.contains("Lives in Montreal"));

        // ---- Reader side: a fresh interceptor + fresh backend handle ----
        //
        // For Sqlite this re-opens the file; for Redis this opens a fresh
        // Lettuce connection to the same key prefix; for InMemory this
        // reuses the backend (in-memory has no cross-instance story — the
        // baseline just proves the composition works).
        var readBackend = "InMemory".equals(label) ? writeBackend : backendFactory.get();
        var readInterceptor = new LongTermMemoryInterceptor(
                readBackend, new StaticFactsStrategy(List.of()), runtime, 10);

        var augmented = readInterceptor.preProcess(
                new AiRequest("status?", "you are helpful").withUserId(userId),
                null);

        assertNotNull(augmented.systemPrompt());
        assertTrue(augmented.systemPrompt().contains("Has a dog named Max"),
                label + ": fresh interceptor should retrieve persisted facts");
        assertTrue(augmented.systemPrompt().contains("Lives in Montreal"),
                label + ": both facts should appear in injected prompt");
        assertFalse(augmented.systemPrompt().equals("you are helpful"),
                label + ": system prompt should be augmented with known-facts block");

        // Cleanup so the next parameterization starts clean.
        readBackend.clear(userId);
        if (readBackend != writeBackend) {
            closeQuietly(readBackend);
        }
        closeQuietly(writeBackend);
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

    /**
     * Deterministic {@link MemoryExtractionStrategy} that returns a fixed
     * list of facts whenever {@code extractFacts} is called. Avoids the need
     * for a real LLM in the integration test while still exercising the
     * interceptor's full extract → persist → retrieve path.
     */
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
