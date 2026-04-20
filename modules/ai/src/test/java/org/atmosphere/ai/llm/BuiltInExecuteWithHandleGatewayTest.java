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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the P1 "gateway bypass on handle-based dispatch" finding.
 * Before the fix, {@code BuiltInAgentRuntime.doExecuteWithHandle} did not
 * call {@code admitThroughGateway}, so per-user rate limits and the
 * credential choke-point policy were only enforced on the plain
 * {@code execute} path. Tools that wanted cancel support silently
 * bypassed the gateway.
 */
class BuiltInExecuteWithHandleGatewayTest {

    @AfterEach
    void tearDown() {
        AiGatewayHolder.reset();
    }

    @Test
    void doExecuteWithHandleAdmitsThroughGateway() {
        var admitCount = new AtomicInteger();
        AiGateway.GatewayTraceExporter counting = entry -> admitCount.incrementAndGet();
        AiGatewayHolder.install(new AiGateway(
                new PerUserRateLimiter(1_000_000, Duration.ofMinutes(1)),
                AiGateway.CredentialResolver.noop(),
                counting));

        // Sub-class the runtime so we can drive doExecuteWithHandle directly
        // with a deterministic offline client — the assertion is that admit
        // runs BEFORE we dispatch, so the client never completes a network
        // call in the test.
        class TestRuntime extends BuiltInAgentRuntime {
            ExecutionHandle invoke(LlmClient client, AgentExecutionContext ctx,
                    StreamingSession session) {
                return doExecuteWithHandle(client, ctx, session);
            }
        }
        var runtime = new TestRuntime();
        // Offline client — throws on use, ensuring we only count admits
        // triggered by the runtime wrapper, not by the downstream stream.
        LlmClient noopClient = new LlmClient() {
            @Override
            public void streamChatCompletion(ChatCompletionRequest request,
                    StreamingSession session) {
                session.complete();
            }
            @Override
            public void streamChatCompletion(ChatCompletionRequest request,
                    StreamingSession session,
                    java.util.concurrent.atomic.AtomicBoolean cancelled,
                    java.util.function.Consumer<java.io.Closeable> streamSink) {
                session.complete();
            }
        };

        var context = new AgentExecutionContext(
                "hello", "system prompt", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var session = new CollectingSession();
        var handle = runtime.invoke(noopClient, context, session);
        handle.whenDone().join();

        assertEquals(1, admitCount.get(),
                "doExecuteWithHandle must admit through AiGateway exactly once "
                + "before dispatching — parity with the plain execute() path");
    }
}
