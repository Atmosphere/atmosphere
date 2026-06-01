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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.interactions.InMemoryInteractionStore;
import org.atmosphere.interactions.InteractionQuery;
import org.atmosphere.interactions.InteractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Interactions REST surface is default-deny on mutating operations
 * (Correctness Invariant #6): create requires both the feature flag and an
 * authenticated principal, and reads are ownership-scoped to the caller.
 */
class InteractionsEndpointAuthzTest {

    private InMemoryInteractionStore store;
    private InteractionService service;
    private MockMvc writeDisabled;
    private MockMvc writeEnabled;

    @BeforeEach
    void setUp() {
        AgentRuntime runtime = new AgentRuntime() {
            @Override public String name() {
                return "test";
            }

            @Override public boolean isAvailable() {
                return true;
            }

            @Override public int priority() {
                return 0;
            }

            @Override public void configure(AiConfig.LlmSettings settings) {
                // no-op
            }

            @Override public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("ok");
                session.complete();
            }
        };
        store = new InMemoryInteractionStore();
        service = new InteractionService(runtime, store);
        writeDisabled = MockMvcBuilders.standaloneSetup(
                new InteractionsEndpoint(service, env(false))).build();
        writeEnabled = MockMvcBuilders.standaloneSetup(
                new InteractionsEndpoint(service, env(true))).build();
    }

    private static Environment env(boolean writeEnabled) {
        var env = new MockEnvironment();
        env.setProperty("atmosphere.interactions.http-write-enabled", String.valueOf(writeEnabled));
        return env;
    }

    private static Principal as(String name) {
        return () -> name;
    }

    @Test
    void createReturns403WhenWriteDisabled() throws Exception {
        writeDisabled.perform(post("/api/interactions")
                        .principal(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReturns401ForAnonymousWhenWriteEnabled() throws Exception {
        writeEnabled.perform(post("/api/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createSucceedsWithPrincipalAndWriteEnabled() throws Exception {
        writeEnabled.perform(post("/api/interactions")
                        .principal(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void getIsOwnershipScoped() throws Exception {
        writeEnabled.perform(post("/api/interactions")
                        .principal(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
        var id = store.list(InteractionQuery.forUser("alice")).get(0).id();

        writeEnabled.perform(get("/api/interactions/" + id).principal(as("alice")))
                .andExpect(status().isOk());
        writeEnabled.perform(get("/api/interactions/" + id).principal(as("bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedIdReturns400() throws Exception {
        // '@' routes as a single clean path segment but fails id validation,
        // so the boundary rejection surfaces as 400 rather than 500.
        writeEnabled.perform(get("/api/interactions/bad@id").principal(as("alice")))
                .andExpect(status().isBadRequest());
    }
}
