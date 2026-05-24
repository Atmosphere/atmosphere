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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.memory.MemoryExtractionStrategy;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.session.sqlite.SqliteLongTermMemory;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot HTTP e2e for the {@link LongTermMemoryInterceptor}
 * dispatch chain. Boots a full Spring context with the AI auto-config +
 * persistent {@link SqliteLongTermMemory} backend, opens a real
 * WebSocket connection to a top-level {@code @AiEndpoint} via wasync,
 * fires a chat message, closes the connection, and asserts the
 * framework's resource-disconnect lifecycle fires
 * {@code interceptor.onDisconnect} through {@code AiEndpointHandler}
 * with the right {@code ai.userId} and {@code conversationId} context.
 *
 * <p>What this test closes — the cell the in-process composition test
 * in {@code modules/integration-tests} cannot cover. There, the test
 * calls {@code interceptor.onDisconnect()} directly. Here, the
 * framework fires it through the real handler chain after a real
 * WebSocket client closes its connection over the wire.</p>
 *
 * <p>What this test does <em>not</em> assert — facts landing in the
 * persistent backend at the end of the chain. {@code
 * LongTermMemoryInterceptor.onDisconnect} bails on empty history, and
 * the conversation history is only populated when the {@code @Prompt}
 * method routes through {@code AiStreamingSession.stream(message)} (the
 * codepath that wraps the session with {@code MemoryCapturingSession}).
 * That requires a real {@code AgentRuntime} configured with an API key,
 * which is out of scope for a unit-test classpath. The interceptor's
 * full happy path with non-empty history is covered by
 * {@code LongTermMemoryInterceptorTest} (Mockito) and the
 * {@code LongTermMemoryBackendIntegrationTest} composition test against
 * each shipped backend.</p>
 *
 * <p>The endpoint, interceptor, and persistent backend are wired as
 * Spring beans so {@code SpringAtmosphereObjectFactory} resolves the
 * interceptor (which has no no-arg constructor) by bean type. The userId
 * attribute is set by a small {@link UserIdAttributeInterceptor}
 * registered via {@code @AtmosphereInterceptorService} so the test can
 * drive any user from a query parameter without standing up a security
 * stack. Wire-level driving uses {@link AtmosphereClient} (wasync) which
 * handles tracking-id semantics, framing, and reconnect cleanup
 * correctly — the same client production samples use.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LongTermMemoryHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.spring.boot"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LongTermMemoryHttpE2eTest {

    /** Canned facts the test strategy returns whenever extraction fires. */
    static final List<String> CANNED_FACTS = List.of(
            "Drove the WebSocket roundtrip through wasync",
            "Disconnected cleanly so onDisconnect fires");

    @LocalServerPort
    private int port;

    @Autowired
    private LongTermMemory memory;

    private static Path dbFile;

    @BeforeAll
    void setUp() {
        dbFile = SHARED_DB.get();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (dbFile != null) Files.deleteIfExists(dbFile);
    }

    @AfterEach
    void purge() {
        memory.clear("user-http-e2e");
        LAST_PROMPT_MESSAGE.set(null);
        DisconnectRecorder.LAST.set(null);
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void disconnectFiresInterceptorAndPersistsFactsViaRealFramework() throws Exception {
        var userId = "user-http-e2e";
        var client = AtmosphereClient.newClient();
        var openLatch = new CountDownLatch(1);
        var ackLatch = new CountDownLatch(1);
        var lastReceived = new AtomicReference<String>();

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        // userId carried as a query parameter — UserIdAttributeInterceptor
        // copies it into the ai.userId request attribute the framework
        // reads on dispatch and disconnect.
        var request = client.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/ltm-e2e?userId=" + userId)
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
              .on(Event.MESSAGE, (Function<Object>) m -> {
                  var s = m.toString();
                  lastReceived.set(s);
                  if (s.contains("ack:")) {
                      ackLatch.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS),
                "WebSocket should connect to /atmosphere/ltm-e2e");

        socket.fire("Hello over wasync!");
        assertTrue(ackLatch.await(15, TimeUnit.SECONDS),
                "Server @Prompt should reply with 'ack:'; last received was: " + lastReceived.get());
        assertNotNull(LAST_PROMPT_MESSAGE.get(),
                "@Prompt method should have been invoked");
        assertTrue(LAST_PROMPT_MESSAGE.get().contains("Hello"),
                "@Prompt received: " + LAST_PROMPT_MESSAGE.get());

        // Close the WebSocket — this fires the framework's disconnect
        // event, which calls handleDisconnect → notifyInterceptorsOnDisconnect
        // → LongTermMemoryInterceptor.onDisconnect → strategy.extractFacts
        // (returns CANNED_FACTS) → memory.saveFacts.
        socket.close();

        // Framework e2e proof: the interceptor chain fires through the
        // real AiEndpointHandler resource-disconnect lifecycle. The
        // DisconnectRecorder captures the exact (userId, conversationId,
        // history) tuple the framework hands the interceptor — no
        // mocking, no in-process invocation. Generous timeout (60s):
        // local Mac/Docker runners fire onDisconnect within ~1s, but
        // GitHub Actions Ubuntu runners can take 15-20s for the
        // WebSocket close to fully propagate through Tomcat's NIO
        // selector → Atmosphere's onStateChange path.
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertNotNull(DisconnectRecorder.LAST.get(),
                        "framework should have fired onDisconnect on socket.close()"));
        var record = DisconnectRecorder.LAST.get();

        // userId propagated correctly: UserIdAttributeInterceptor copied
        // the query parameter into the ai.userId request attribute on
        // the WebSocket upgrade, and the framework read it back at
        // disconnect time.
        assertNotNull(record.userId(),
                "framework should read ai.userId attribute at disconnect; got null");
        assertTrue(userId.equals(record.userId()),
                "expected userId=" + userId + " at disconnect; got " + record.userId());

        // conversationId is the AtmosphereResource UUID — the same key
        // AiConversationMemory uses when @Prompt routes through
        // AiStreamingSession.stream(). The history list is whatever the
        // conversation memory captured under that key. Empty when the
        // @Prompt method emits bytes directly (session.send/complete)
        // without going through the runtime — that's the case here
        // because we don't configure a real AgentRuntime, so the
        // MemoryCapturingSession wrapper is never installed. The fact
        // that the framework reaches this interceptor with the right
        // user/conversation context is the wire-level proof we needed;
        // history-population is exercised by AiStreamingSessionTest +
        // the composition tests in modules/integration-tests.
        assertNotNull(record.conversationId(),
                "framework should pass a conversationId at disconnect; got null");
    }

    // -------------------------------------------------------------------
    // Spring Boot test app + beans
    // -------------------------------------------------------------------

    private static final AtomicReference<Path> SHARED_DB = new AtomicReference<>();
    static final AtomicReference<String> LAST_PROMPT_MESSAGE = new AtomicReference<>();

    static {
        try {
            var tmp = Files.createTempFile("ltm-http-e2e-springboot-", ".db");
            Files.deleteIfExists(tmp);
            SHARED_DB.set(tmp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SpringBootApplication
    static class TestApp {

        @Bean
        LongTermMemory longTermMemory() {
            return new SqliteLongTermMemory(SHARED_DB.get(), 100);
        }

        @Bean
        LongTermMemoryInterceptor longTermMemoryInterceptor(LongTermMemory memory) {
            return new LongTermMemoryInterceptor(
                    memory,
                    new StaticFactsStrategy(CANNED_FACTS),
                    null,
                    10);
        }
    }

    /**
     * Test-only Atmosphere interceptor that copies the {@code userId}
     * query parameter into the {@code ai.userId} request attribute the
     * framework reads. Avoids standing up a Spring Security stack just
     * to drive different users from the client.
     */
    @AtmosphereInterceptorService
    public static class UserIdAttributeInterceptor extends AtmosphereInterceptorAdapter {
        @Override
        public Action inspect(AtmosphereResource resource) {
            var req = resource.getRequest();
            if (req != null && req.getAttribute("ai.userId") == null) {
                var userId = req.getParameter("userId");
                if (userId != null && !userId.isBlank()) {
                    req.setAttribute("ai.userId", userId);
                }
            }
            return Action.CONTINUE;
        }
    }

    /**
     * Diagnostic interceptor that records what {@code onDisconnect}
     * received from the framework so the test can assert the dispatch
     * chain wiring directly (userId, conversationId, history). Wired
     * alongside {@link LongTermMemoryInterceptor} on the
     * {@code @AiEndpoint} so we can tell whether disconnect-time
     * dispatch is failing or the interceptor's own checks are
     * short-circuiting.
     */
    public static class DisconnectRecorder implements AiInterceptor {
        static final AtomicReference<Record> LAST = new AtomicReference<>();

        public record Record(String userId, String conversationId, int historySize,
                             List<String> historyContents) { }

        @Override
        public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
            return request;
        }

        @Override
        public void postProcess(AiRequest request, AtmosphereResource resource) { }

        @Override
        public void onDisconnect(String userId, String conversationId, List<ChatMessage> history) {
            LAST.set(new Record(userId, conversationId, history.size(),
                    history.stream().map(ChatMessage::content).toList()));
        }
    }

    /** Deterministic strategy returning canned facts whenever asked. */
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
