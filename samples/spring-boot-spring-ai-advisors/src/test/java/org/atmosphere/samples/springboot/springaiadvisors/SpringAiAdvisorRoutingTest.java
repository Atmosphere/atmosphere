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
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.spring.SpringAiAdvisors;
import org.atmosphere.ai.spring.SpringAiAgentRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.atmosphere.samples.springboot.springaiadvisors.BoundChatClientConfig.DEFAULT_ADVISOR_NAME;
import static org.atmosphere.samples.springboot.springaiadvisors.BoundChatClientConfig.PER_REQUEST_ADVISOR_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery test for the three Atmosphere 4 blog §3 claims. It drives the real
 * {@link SpringAiAgentRuntime} (the same dispatch the {@code @AiEndpoint} uses
 * via {@code session.stream(...)}) over the real bound Spring AI
 * {@code ChatClient} this sample configures — and asserts the advisors actually
 * <em>ran</em> by checking their observable side effect in {@link AdvisorAuditLog}.
 *
 * <p>No "bean exists" assertions: every assertion is keyed off an advisor's
 * recorded invocation inside the {@code ChatClient} advisor chain.</p>
 */
class SpringAiAdvisorRoutingTest {

    private final AdvisorAuditLog auditLog = AdvisorAuditLog.shared();
    private SpringAiAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        auditLog.clear();
        // Claim #1: bind our OWN fully-configured ChatClient (LocalEchoChatModel
        // + a default AuditingAdvisor) via SpringAiAgentRuntime.setChatClient(...)
        // — exactly the wiring the running application performs at startup.
        BoundChatClientConfig.bindBoundChatClient(auditLog);
        runtime = new SpringAiAgentRuntime();
    }

    /**
     * Claim #2: Atmosphere keeps the {@code defaultAdvisors(...)} on the bound
     * client. A normal request runs the default advisor exactly once and runs
     * no per-request advisor; the echo from the terminal model proves the
     * request travelled the whole chain rather than being short-circuited.
     */
    @Test
    void defaultAdvisorRunsThroughBoundClientOnEveryRequest() {
        var session = new CollectingSession();
        runtime.execute(context("Hello advisors", Map.of()), session);

        assertEquals(1, auditLog.count(DEFAULT_ADVISOR_NAME),
                "the default advisor configured on the bound ChatClient must run "
                        + "on a normal request — proving Atmosphere kept defaultAdvisors(...)");
        assertEquals(0, auditLog.count(PER_REQUEST_ADVISOR_NAME),
                "no per-request advisor was attached, so it must NOT run");
        assertFalse(session.errored(), () -> "request errored: " + session.error());
        assertTrue(session.text().contains("[local-echo]"),
                "the request must reach the terminal model through the advisor chain "
                        + "(its echo is the proof), not be answered by a bare model call");
    }

    /**
     * Claim #3: a second advisor attached per request also runs — and it is
     * attached the way the live {@code @AiEndpoint} attaches it: through
     * {@link PerRequestAuditInterceptor} stamping request metadata, which
     * Atmosphere copies onto the {@code AgentExecutionContext} and the runtime
     * reads back via {@code SpringAiAdvisors.from(context)}. Both the default
     * AND the per-request advisor fire on that single request.
     */
    @Test
    void perRequestAdvisorAttachedByInterceptorAlsoRuns() {
        var interceptor = new PerRequestAuditInterceptor();
        // The interceptor triggers on the word "audit"; resource is unused.
        var processed = interceptor.preProcess(
                new AiRequest("please audit this answer", "You are a concise assistant."), null);

        var ctx = context(processed.message(), processed.metadata());
        // Sanity: the interceptor genuinely placed one advisor in the metadata
        // the runtime will read — not a no-op.
        assertEquals(1, SpringAiAdvisors.from(ctx).size(),
                "the interceptor must attach exactly one per-request advisor for an "
                        + "'audit' request");

        var session = new CollectingSession();
        runtime.execute(ctx, session);

        assertEquals(1, auditLog.count(DEFAULT_ADVISOR_NAME),
                "the default advisor still runs alongside the per-request one");
        assertEquals(1, auditLog.count(PER_REQUEST_ADVISOR_NAME),
                "the per-request advisor attached by the interceptor must actually run "
                        + "inside the ChatClient chain on this request");
        assertFalse(session.errored(), () -> "request errored: " + session.error());
        assertTrue(session.text().contains("[local-echo]"),
                "the request must still reach the terminal model through the chain");
    }

    /**
     * The per-request advisor is genuinely per-request: a message without the
     * trigger word attaches nothing, so only the default advisor runs.
     */
    @Test
    void nonTriggerRequestAttachesNoPerRequestAdvisor() {
        var interceptor = new PerRequestAuditInterceptor();
        var processed = interceptor.preProcess(
                new AiRequest("just say hello", "You are a concise assistant."), null);
        var ctx = context(processed.message(), processed.metadata());

        assertTrue(SpringAiAdvisors.from(ctx).isEmpty(),
                "a non-'audit' request must carry no per-request advisor");

        runtime.execute(ctx, new CollectingSession());

        assertEquals(1, auditLog.count(DEFAULT_ADVISOR_NAME),
                "the default advisor always runs");
        assertEquals(0, auditLog.count(PER_REQUEST_ADVISOR_NAME),
                "no per-request advisor was attached, so none runs");
    }

    private static AgentExecutionContext context(String message, Map<String, Object> metadata) {
        return new AgentExecutionContext(
                message, "You are a concise assistant.", "local-echo",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(),
                metadata != null ? metadata : Map.of(), List.of(), null, null);
    }

    /**
     * Minimal {@link StreamingSession} that accumulates the streamed text and
     * captures any terminal error, so the test can prove the request actually
     * traversed the model (echo present) and completed cleanly.
     */
    private static final class CollectingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private volatile boolean closed;
        private volatile Throwable error;

        @Override public String sessionId() { return "advisor-routing-test"; }
        @Override public void send(String chunk) { text.append(chunk); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { this.error = t; this.closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return error != null; }

        String text() { return text.toString(); }
        boolean errored() { return error != null; }
        Throwable error() { return error; }
    }
}
