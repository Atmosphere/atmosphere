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
package org.atmosphere.ai.batch;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the batch endpoint through the real handler seam ({@code onRequest}
 * over mocked Atmosphere request/response), with items dispatching through a
 * real {@link AiPipeline} — including the governance-applies proof: a
 * guardrail observably fires for every batch item.
 */
class BatchHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BatchServing ENABLED =
            new BatchServing(true, null, null, 8, 100, 4, 30_000L, 200);

    private BatchHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.destroy();
            handler = null;
        }
    }

    /** Captures the execution context and delegates streaming to a scripted behavior. */
    static final class StubRuntime implements AgentRuntime {

        private final BiConsumer<AgentExecutionContext, StreamingSession> behavior;

        StubRuntime(BiConsumer<AgentExecutionContext, StreamingSession> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            behavior.accept(context, session);
        }
    }

    record Rig(org.atmosphere.cpr.AtmosphereResource resource, StringWriter output,
               org.atmosphere.cpr.AtmosphereResponse response) {
    }

    private static Rig rig(String method, String uri, String contentType,
                           String authorization, String body) throws Exception {
        var resource = mock(org.atmosphere.cpr.AtmosphereResource.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        var response = mock(org.atmosphere.cpr.AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.uuid()).thenReturn("test-uuid");
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContentType()).thenReturn(contentType);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        var output = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        return new Rig(resource, output, response);
    }

    private static AiPipeline pipeline(AgentRuntime runtime, List<AiGuardrail> guardrails) {
        return new AiPipeline(runtime, "You are a governed test agent.", "stub-model",
                null, null, guardrails, List.of(), null);
    }

    private BatchHandler handler(BatchServing serving, AgentRuntime runtime,
                                 List<AiGuardrail> guardrails) {
        var store = new InMemoryBatchJobStore(serving.retainedTerminalJobs());
        var executor = new BatchExecutor(store, serving.maxOpenJobs(), serving.maxItemsPerJob(),
                serving.itemConcurrency(), Duration.ofMillis(serving.itemTimeoutMs()));
        handler = new BatchHandler(serving, executor, store);
        handler.register("demo", pipeline(runtime, guardrails), null);
        return handler;
    }

    private static String submitBody(String... inputs) {
        var items = new StringBuilder();
        for (int i = 0; i < inputs.length; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"custom_id\":\"c").append(i).append("\",\"input\":\"")
                    .append(inputs[i]).append("\"}");
        }
        return "{\"agent\":\"demo\",\"items\":[" + items + "]}";
    }

    private String submitAndAwait(BatchHandler target, String body) throws Exception {
        var submit = rig("POST", BatchServing.BATCHES_PATH, "application/json", null, body);
        target.onRequest(submit.resource());
        verify(submit.response()).setStatus(202);
        var node = MAPPER.readTree(submit.output().toString());
        assertEquals("batch", node.get("object").stringValue());
        assertEquals("queued", node.get("status").stringValue());
        var id = node.get("id").stringValue();
        var terminal = target.executor().awaitTerminal(id, Duration.ofSeconds(10));
        assertTrue(terminal.isPresent() && terminal.get().status().terminal(),
                "job must reach a terminal state");
        return id;
    }

    @Test
    void submitPollAndFetchResultsHappyPath() throws Exception {
        var runtime = new StubRuntime((context, session) -> {
            session.send("echo:" + context.message());
            session.complete();
        });
        var target = handler(ENABLED, runtime, List.of());
        var id = submitAndAwait(target, submitBody("one", "two"));

        var poll = rig("GET", BatchServing.BATCHES_PATH + "/" + id, null, null, "");
        target.onRequest(poll.resource());
        verify(poll.response()).setStatus(200);
        var job = MAPPER.readTree(poll.output().toString());
        assertEquals("completed", job.get("status").stringValue());
        assertEquals(2, job.get("counts").get("total").asInt());
        assertEquals(2, job.get("counts").get("succeeded").asInt());
        assertEquals(0, job.get("counts").get("failed").asInt());

        var results = rig("GET", BatchServing.BATCHES_PATH + "/" + id + "/results",
                null, null, "");
        target.onRequest(results.resource());
        verify(results.response()).setStatus(200);
        var body = MAPPER.readTree(results.output().toString());
        assertEquals(id, body.get("id").stringValue());
        assertEquals("c0", body.get("data").get(0).get("custom_id").stringValue());
        assertEquals("echo:one", body.get("data").get(0).get("output").stringValue());
        assertEquals("succeeded", body.get("data").get(0).get("status").stringValue());
        assertEquals("echo:two", body.get("data").get(1).get("output").stringValue());

        var list = rig("GET", BatchServing.BATCHES_PATH, null, null, "");
        target.onRequest(list.resource());
        verify(list.response()).setStatus(200);
        var listing = MAPPER.readTree(list.output().toString());
        assertEquals("list", listing.get("object").stringValue());
        assertEquals(id, listing.get("data").get(0).get("id").stringValue());
    }

    @Test
    void perItemFailureIsRecordedWithoutKillingTheJob() throws Exception {
        var runtime = new StubRuntime((context, session) -> {
            if (context.message().contains("boom")) {
                session.error(new IllegalStateException("kaboom"));
            } else {
                session.send("ok");
                session.complete();
            }
        });
        var target = handler(ENABLED, runtime, List.of());
        var id = submitAndAwait(target, submitBody("fine", "boom", "fine"));

        var poll = rig("GET", BatchServing.BATCHES_PATH + "/" + id, null, null, "");
        target.onRequest(poll.resource());
        var job = MAPPER.readTree(poll.output().toString());
        assertEquals("completed", job.get("status").stringValue());
        assertEquals(2, job.get("counts").get("succeeded").asInt());
        assertEquals(1, job.get("counts").get("failed").asInt());

        var results = rig("GET", BatchServing.BATCHES_PATH + "/" + id + "/results",
                null, null, "");
        target.onRequest(results.resource());
        var body = MAPPER.readTree(results.output().toString());
        assertEquals("failed", body.get("data").get(1).get("status").stringValue());
        // Internal failure details stay in the server log, not the wire.
        assertEquals("item failed with an internal error",
                body.get("data").get(1).get("error").stringValue());
        assertTrue(body.get("data").get(1).get("output").isNull());
    }

    @Test
    void guardrailFiresForEveryBatchItemAndBlocksMatchingOnes() throws Exception {
        var inspected = new AtomicInteger();
        var guardrail = new AiGuardrail() {
            @Override
            public GuardrailResult inspectRequest(AiRequest request) {
                inspected.incrementAndGet();
                if (request.message().contains("forbidden")) {
                    return GuardrailResult.block("forbidden content");
                }
                return GuardrailResult.pass();
            }
        };
        var runtime = new StubRuntime((context, session) -> {
            session.send("ok");
            session.complete();
        });
        var target = handler(ENABLED, runtime, List.of(guardrail));
        var id = submitAndAwait(target, submitBody("fine", "forbidden thing"));

        // Governance-applies proof: the guardrail ran once per item because
        // every item dispatched through AiPipeline (Mode Parity #7).
        assertEquals(2, inspected.get());

        var results = rig("GET", BatchServing.BATCHES_PATH + "/" + id + "/results",
                null, null, "");
        target.onRequest(results.resource());
        var body = MAPPER.readTree(results.output().toString());
        assertEquals("succeeded", body.get("data").get(0).get("status").stringValue());
        assertEquals("failed", body.get("data").get(1).get("status").stringValue());
        // Guardrail denials carry the caller-meaningful reason.
        assertEquals("Request blocked: forbidden content",
                body.get("data").get(1).get("error").stringValue());
    }

    @Test
    void cancelMidRunLeavesJobAndItemsTerminalCancelled() throws Exception {
        var gate = new CountDownLatch(1);
        var runtime = new StubRuntime((context, session) -> {
            try {
                gate.await();
                session.send("late");
                session.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var target = handler(ENABLED, runtime, List.of());
        try {
            var submit = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                    submitBody("a", "b", "c"));
            target.onRequest(submit.resource());
            verify(submit.response()).setStatus(202);
            var id = MAPPER.readTree(submit.output().toString()).get("id").stringValue();

            var cancel = rig("POST", BatchServing.BATCHES_PATH + "/" + id + "/cancel",
                    null, null, "");
            target.onRequest(cancel.resource());
            verify(cancel.response()).setStatus(200);
            assertTrue(target.executor().awaitTerminal(id, Duration.ofSeconds(10))
                    .map(job -> job.status() == BatchJob.Status.CANCELLED).orElse(false),
                    "cancelled job must reach the CANCELLED terminal state");

            var poll = rig("GET", BatchServing.BATCHES_PATH + "/" + id, null, null, "");
            target.onRequest(poll.resource());
            var job = MAPPER.readTree(poll.output().toString());
            assertEquals("cancelled", job.get("status").stringValue());
            assertEquals(0, job.get("counts").get("pending").asInt());
            assertEquals(3, job.get("counts").get("cancelled").asInt()
                    + job.get("counts").get("failed").asInt()
                    + job.get("counts").get("succeeded").asInt());

            // Cancelling a terminal job is a 409, not a silent no-op.
            var again = rig("POST", BatchServing.BATCHES_PATH + "/" + id + "/cancel",
                    null, null, "");
            target.onRequest(again.resource());
            verify(again.response()).setStatus(409);
        } finally {
            gate.countDown();
        }
    }

    @Test
    void overLimitItemCountReturns429() throws Exception {
        var serving = new BatchServing(true, null, null, 8, 2, 4, 30_000L, 200);
        var runtime = new StubRuntime((context, session) -> session.complete("ok"));
        var target = handler(serving, runtime, List.of());

        var submit = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                submitBody("a", "b", "c"));
        target.onRequest(submit.resource());
        verify(submit.response()).setStatus(429);
        var node = MAPPER.readTree(submit.output().toString());
        assertEquals("over_capacity", node.get("error").get("code").stringValue());
        assertEquals(BatchError.TYPE_RATE_LIMIT, node.get("error").get("type").stringValue());
    }

    @Test
    void overOpenJobLimitReturns429() throws Exception {
        var serving = new BatchServing(true, null, null, 1, 100, 4, 30_000L, 200);
        var gate = new CountDownLatch(1);
        var runtime = new StubRuntime((context, session) -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
                session.complete("ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var target = handler(serving, runtime, List.of());
        try {
            var first = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                    submitBody("a"));
            target.onRequest(first.resource());
            verify(first.response()).setStatus(202);

            var second = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                    submitBody("b"));
            target.onRequest(second.resource());
            verify(second.response()).setStatus(429);
            var node = MAPPER.readTree(second.output().toString());
            assertEquals("over_capacity", node.get("error").get("code").stringValue());
        } finally {
            gate.countDown();
        }
    }

    @Test
    void configuredApiKeyGatesSubmitAndCancel() throws Exception {
        var serving = new BatchServing(true, "sk-batch", null, 8, 100, 4, 30_000L, 200);
        var runtime = new StubRuntime((context, session) -> session.complete("ok"));
        var target = handler(serving, runtime, List.of());

        var denied = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                submitBody("a"));
        target.onRequest(denied.resource());
        verify(denied.response()).setStatus(401);
        assertEquals("invalid_api_key", MAPPER.readTree(denied.output().toString())
                .get("error").get("code").stringValue());

        var cancelDenied = rig("POST", BatchServing.BATCHES_PATH + "/batch-x/cancel",
                null, null, "");
        target.onRequest(cancelDenied.resource());
        verify(cancelDenied.response()).setStatus(401);

        var allowed = rig("POST", BatchServing.BATCHES_PATH, "application/json",
                "Bearer sk-batch", submitBody("a"));
        target.onRequest(allowed.resource());
        verify(allowed.response()).setStatus(202);
    }

    @Test
    void malformedAndInvalidSubmissionsReturn4xxEnvelopes() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete("ok"));
        var target = handler(ENABLED, runtime, List.of());

        var malformed = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                "not json {{{");
        target.onRequest(malformed.resource());
        verify(malformed.response()).setStatus(400);

        var noAgent = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                "{\"items\":[{\"input\":\"x\"}]}");
        target.onRequest(noAgent.resource());
        verify(noAgent.response()).setStatus(400);
        assertEquals("agent", MAPPER.readTree(noAgent.output().toString())
                .get("error").get("param").stringValue());

        var unknownAgent = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                "{\"agent\":\"nope\",\"items\":[{\"input\":\"x\"}]}");
        target.onRequest(unknownAgent.resource());
        verify(unknownAgent.response()).setStatus(404);
        assertEquals("agent_not_found", MAPPER.readTree(unknownAgent.output().toString())
                .get("error").get("code").stringValue());

        var emptyItems = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                "{\"agent\":\"demo\",\"items\":[]}");
        target.onRequest(emptyItems.resource());
        verify(emptyItems.response()).setStatus(400);

        var wrongType = rig("POST", BatchServing.BATCHES_PATH, "text/plain", null,
                submitBody("a"));
        target.onRequest(wrongType.resource());
        verify(wrongType.response()).setStatus(415);

        var wrongMethod = rig("PUT", BatchServing.BATCHES_PATH, "application/json", null, "{}");
        target.onRequest(wrongMethod.resource());
        verify(wrongMethod.response()).setStatus(405);

        var getOnCancel = rig("GET", BatchServing.BATCHES_PATH + "/batch-x/cancel",
                null, null, "");
        target.onRequest(getOnCancel.resource());
        verify(getOnCancel.response()).setStatus(405);

        var badId = rig("GET", BatchServing.BATCHES_PATH + "/not%20valid!", null, null, "");
        target.onRequest(badId.resource());
        verify(badId.response()).setStatus(400);

        var unknownJob = rig("GET", BatchServing.BATCHES_PATH + "/batch-doesnotexist",
                null, null, "");
        target.onRequest(unknownJob.resource());
        verify(unknownJob.response()).setStatus(404);
        assertEquals("job_not_found", MAPPER.readTree(unknownJob.output().toString())
                .get("error").get("code").stringValue());
    }

    @Test
    void oversizedBodyReturns413() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete("ok"));
        var target = handler(ENABLED, runtime, List.of());
        var big = "x".repeat(BatchHandler.MAX_BODY_CHARS + 1);
        var submit = rig("POST", BatchServing.BATCHES_PATH, "application/json", null, big);
        target.onRequest(submit.resource());
        verify(submit.response()).setStatus(413);
    }

    @Test
    void resultsAreReadableWhileTheJobStillRuns() throws Exception {
        var gate = new CountDownLatch(1);
        var runtime = new StubRuntime((context, session) -> {
            if (context.message().equals("slow")) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            session.send("done:" + context.message());
            session.complete();
        });
        var target = handler(ENABLED, runtime, List.of());
        try {
            var submit = rig("POST", BatchServing.BATCHES_PATH, "application/json", null,
                    "{\"agent\":\"demo\",\"items\":[{\"custom_id\":\"fast\",\"input\":\"quick\"},"
                            + "{\"custom_id\":\"slow-one\",\"input\":\"slow\"}]}");
            target.onRequest(submit.resource());
            var id = MAPPER.readTree(submit.output().toString()).get("id").stringValue();

            // Wait (bounded) for the fast item to land while the slow one holds
            // the job open, then read partial results.
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                var items = target.executor().store().items(id);
                if (!items.isEmpty() && items.get(0).status().terminal()) {
                    break;
                }
                Thread.onSpinWait();
            }
            var results = rig("GET", BatchServing.BATCHES_PATH + "/" + id + "/results",
                    null, null, "");
            target.onRequest(results.resource());
            verify(results.response()).setStatus(200);
            var body = MAPPER.readTree(results.output().toString());
            assertEquals("succeeded", body.get("data").get(0).get("status").stringValue());
            assertEquals("pending", body.get("data").get(1).get("status").stringValue());
            assertTrue(body.get("data").get(1).get("output").isNull());
        } finally {
            gate.countDown();
        }
        var done = target.executor().awaitTerminal(
                target.executor().store().jobs(1).get(0).id(), Duration.ofSeconds(10));
        assertNotNull(done.orElse(null));
    }
}
