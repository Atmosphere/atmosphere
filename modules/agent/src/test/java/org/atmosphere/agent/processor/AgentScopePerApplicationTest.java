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
package org.atmosphere.agent.processor;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.scope.ScopeGuardrailResolver;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code @Agent}'s skill-derived scope screens with its own application's
 * embedding runtime on the web path and the pipeline path, while another
 * application runs in the JVM and the registering thread is bound to neither.
 * The scope used to take the guardrail the JVM-wide resolver built from the
 * static API, which then was the process default's, so the agent's
 * embedding-tier scope never reached its application's runtime.
 */
class AgentScopePerApplicationTest {

    private static final String EMBEDDING_TIER_SKILL = """
            ---
            description: "Order-status support for an online store"
            ---
            # Support Bot

            ## Guardrails
            - gambling
            """;

    private HttpServer server;
    private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
    private AtmosphereFramework framework;
    private AiEnvironment first;
    private AiEnvironment second;

    @BeforeEach
    void setUp() throws Exception {
        AiConfig.resetHostConfig();
        ScopeGuardrailResolver.reset();
        EmbeddingRuntimeResolver.resetCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            requests.add(path);
            exchange.getRequestBody().readAllBytes();
            var ok = path.endsWith("/embeddings");
            var body = (ok ? "{\"data\":[{\"embedding\":[1.0,0.0,0.0]}]}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 400, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        framework = new AtmosphereFramework();
        first = AiEnvironment.create(framework.getAtmosphereConfig(), key -> null);
        first.configure("remote", "chat-first", "key-first", base + "/first/v1");
        second = AiEnvironment.create(new AtmosphereFramework().getAtmosphereConfig(), key -> null);
        second.configure("remote", "chat-second", "key-second", base + "/second/v1");
    }

    @AfterEach
    void tearDown() {
        second.close();
        first.close();
        server.stop(0);
        ScopeGuardrailResolver.reset();
        EmbeddingRuntimeResolver.resetCache();
        AiConfig.resetHostConfig();
    }

    @Test
    void webPathScopeEmbedsWithItsApplicationsRuntime() {
        var guardrails = new AgentProcessor().buildWebGuardrails(framework,
                SkillFileParser.parse(EMBEDDING_TIER_SKILL), null, "support-bot", "Order support");

        var scope = assertInstanceOf(PolicyAsGuardrail.class, guardrails.getFirst());
        scope.inspectRequest(new AiRequest("where is my order?"));

        assertOnlyFirstEmbeddings();
    }

    @Test
    void pipelinePathScopeEmbedsWithItsApplicationsRuntime() {
        var pipeline = new AgentProcessor().buildPipeline(framework,
                SkillFileParser.parse(EMBEDDING_TIER_SKILL), null, "support-bot", "Order support",
                new LocalRuntime(), "sys", "model", null, new DefaultToolRegistry(), AiMetrics.NOOP);

        var scope = assertInstanceOf(ScopePolicy.class, pipeline.policies().getFirst());
        scope.evaluate(PolicyContext.preAdmission(new AiRequest("where is my order?")));

        assertOnlyFirstEmbeddings();
    }

    private void assertOnlyFirstEmbeddings() {
        var seen = List.copyOf(requests);
        assertTrue(seen.contains("/first/v1/embeddings"),
                "the agent's scope must embed with its own application's runtime, saw " + seen);
        assertTrue(seen.stream().noneMatch(p -> p.startsWith("/second/")),
                "nothing may reach the other application's endpoint, saw " + seen);
    }

    /** A runtime the pipeline never needs to call here. */
    private static final class LocalRuntime implements AgentRuntime {
        @Override public String name() { return "local"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
