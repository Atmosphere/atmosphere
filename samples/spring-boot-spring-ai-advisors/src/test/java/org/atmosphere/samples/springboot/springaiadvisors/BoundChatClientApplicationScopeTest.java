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
package org.atmosphere.samples.springboot.springaiadvisors;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.spring.SpringAiAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.atmosphere.samples.springboot.springaiadvisors.BoundChatClientConfig.DEFAULT_ADVISOR_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample binds its {@code ChatClient} to its own application: another
 * application in the same JVM (another Spring context, another embedded
 * server) does not dispatch through it. Before, the sample bound it with the
 * process-wide {@code SpringAiAgentRuntime.setChatClient(client)}, which every
 * application's Spring AI runtime used.
 */
class BoundChatClientApplicationScopeTest {

    private final AdvisorAuditLog auditLog = AdvisorAuditLog.shared();
    private final List<AiEnvironment> environments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        auditLog.clear();
    }

    @AfterEach
    void tearDown() {
        environments.forEach(AiEnvironment::close);
        auditLog.clear();
    }

    private AiEnvironment application() {
        var environment = AiEnvironment.create(Map.<String, String>of()::get);
        environments.add(environment);
        return environment;
    }

    private static SpringAiAgentRuntime runtimeOf(AiEnvironment environment) {
        var runtime = new SpringAiAgentRuntime();
        runtime.attach(environment);
        return runtime;
    }

    @Test
    void theBoundClientServesOnlyTheApplicationThatBoundIt() {
        var bound = application();
        var other = application();
        new BoundChatClientConfig(bound).bindAtStartup();

        var otherSession = new TextSession();
        try {
            runtimeOf(other).execute(context(), otherSession);
        } catch (RuntimeException noClientForThisApplication) {
            // The other application has no ChatClient of its own: whether the
            // runtime throws or errors the session, it must not reach ours.
            otherSession.error(noClientForThisApplication);
        }
        assertEquals(0, auditLog.count(DEFAULT_ADVISOR_NAME),
                "another application must not dispatch through the sample's bound ChatClient");
        assertFalse(otherSession.text().contains("[local-echo]"),
                "another application's request must not reach the sample's model");

        var boundSession = new TextSession();
        runtimeOf(bound).execute(context(), boundSession);
        assertEquals(1, auditLog.count(DEFAULT_ADVISOR_NAME),
                "the application that bound the client dispatches through it");
        assertTrue(boundSession.text().contains("[local-echo]"),
                "the bound application's request reaches the sample's model");
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "Hello advisors", "You are a concise assistant.", "local-echo",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null);
    }

    /** Accumulates the streamed text and records a terminal error. */
    private static final class TextSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private volatile boolean closed;
        private volatile Throwable error;

        @Override public String sessionId() { return "bound-client-scope-test"; }
        @Override public void send(String chunk) { text.append(chunk); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { this.error = t; this.closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return error != null; }

        String text() { return text.toString(); }
    }
}
