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
package org.atmosphere.ai.governance;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.rag.InjectionClassifier;
import org.atmosphere.ai.governance.rag.InjectionClassifierResolver;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeGuardrailResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The injection-classifier and scope-guardrail resolvers cache what they
 * build per application: one built over an application's embedding runtime is
 * never handed to another application, so closing the first one (a redeploy,
 * an evicted test context) leaves the classifier and guardrail of the one still
 * running working, instead of failing every embedding call on the closed
 * application's runtime.
 */
// Resets the static AI facade (AiConfig) between tests: deprecated for
// framework code, kept for code that runs with no application.
@SuppressWarnings("deprecation")
class ResolverCacheEnvironmentCloseTest {

    /** Every text embeds to the same vector, so every similarity is 1. */
    private static final String REPLY = "{\"data\":[{\"embedding\":[0.1,0.2]}]}";

    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        AiConfig.resetHostConfig();
        InjectionClassifierResolver.reset();
        ScopeGuardrailResolver.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var reply = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        InjectionClassifierResolver.reset();
        ScopeGuardrailResolver.reset();
        AiConfig.resetHostConfig();
    }

    private AiEnvironment application() {
        var environment = AiEnvironment.create(
                Map.of(AiConfig.EMBEDDING_MODEL_PROPERTY, "text-embedding-3-small")::get);
        environment.configure("remote", "gpt-4o-mini", "k",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        return environment;
    }

    private static InjectionClassifier.Decision screen(InjectionClassifier classifier) {
        return classifier.evaluate(new ContextProvider.Document("a retrieved paragraph", "doc", 1.0));
    }

    private static ScopeGuardrail.Decision scope(ScopeGuardrail guardrail) {
        var config = new ScopeConfig("customer support", List.of(), AgentScope.Breach.POLITE_REDIRECT,
                null, AgentScope.Tier.EMBEDDING_SIMILARITY, 0.5, false, false, null);
        return guardrail.evaluate(new AiRequest("where is my order"), config);
    }

    @Test
    void anInjectionClassifierBuiltForAClosedApplicationIsNotHandedToTheOneStillRunning() {
        var a = application();
        AiEnvironment b = null;
        try {
            // A is the only application: the static resolver resolves for it.
            var ofA = InjectionClassifierResolver.resolve(InjectionClassifier.Tier.EMBEDDING_SIMILARITY);
            assertEquals(InjectionClassifier.Tier.EMBEDDING_SIMILARITY, ofA.tier());
            assertNotEquals(InjectionClassifier.Outcome.ERROR, screen(ofA).outcome());

            b = application();
            a.close();

            // B is now the only application.
            var ofB = InjectionClassifierResolver.resolve(InjectionClassifier.Tier.EMBEDDING_SIMILARITY);
            assertNotSame(ofA, ofB, "the closed application's classifier is not B's");
            var decision = screen(ofB);
            assertNotEquals(InjectionClassifier.Outcome.ERROR, decision.outcome(),
                    "B's classifier embeds through B's runtime: " + decision);
        } finally {
            a.close();
            if (b != null) {
                b.close();
            }
        }
    }

    @Test
    void aScopeGuardrailBuiltForAClosedApplicationIsNotHandedToTheOneStillRunning() {
        var a = application();
        AiEnvironment b = null;
        try {
            var ofA = ScopeGuardrailResolver.resolve(AgentScope.Tier.EMBEDDING_SIMILARITY);
            assertEquals(1.0, scope(ofA).similarity(), 1e-9, "A's guardrail embeds");

            b = application();
            a.close();

            var ofB = ScopeGuardrailResolver.resolve(AgentScope.Tier.EMBEDDING_SIMILARITY);
            assertNotSame(ofA, ofB, "the closed application's guardrail is not B's");
            // A rule-based downgrade reports no similarity: 1.0 means B's
            // guardrail still embeds.
            assertEquals(1.0, scope(ofB).similarity(), 1e-9,
                    "B's guardrail embeds through B's runtime instead of degrading to RULE_BASED");
        } finally {
            a.close();
            if (b != null) {
                b.close();
            }
        }
    }
}
