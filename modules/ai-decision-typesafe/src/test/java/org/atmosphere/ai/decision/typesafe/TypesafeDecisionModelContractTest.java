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
package org.atmosphere.ai.decision.typesafe;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.decision.Question;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests against a JDK {@code HttpServer} stub that replays the request
 * and response shapes documented at {@code docs.typesafe.ai/api.md} (the
 * examples are copied into {@code src/test/resources/fixtures}; the request
 * fixtures change {@code model} from the moving alias {@code jev-latest} to the
 * pinned {@code jev-1.13.0}, the adapter's default) and the
 * error envelope observed live for 401/403.
 */
class TypesafeDecisionModelContractTest {

    private static final String STATE = "Help! My payouts have been failing for 3 days.";

    private TypesafeStub stub;
    private TypesafeDecisionModel model;

    @BeforeEach
    void setUp() {
        // Verdicts are shared per base URL; a new stub can reuse an old port.
        TypesafeDecisionModel.forgetSharedVerdicts();
        stub = new TypesafeStub();
        model = TypesafeDecisionModel.builder()
                .apiKey("test-key")
                .baseUrl(stub.baseUrl())
                .build();
    }

    @AfterEach
    void tearDown() {
        model.close();
        stub.close();
    }

    private static DecisionRequest noulRequest() {
        return DecisionRequest.of(STATE, "is_urgent", new Question.Noul("Does this convey urgency?",
                "Explicitly time-sensitive", "No urgency expressed"));
    }

    private static DecisionRequest choiceRequest() {
        var options = new LinkedHashMap<String, String>();
        options.put("billing", "Payments, invoicing, refunds");
        options.put("technical", "Bugs, outages, integrations");
        options.put("sales", "Pricing, upgrades, new accounts");
        return DecisionRequest.of(STATE, "department",
                new Question.Choice("Which team should handle this?", options));
    }

    private static DecisionRequest scoreRequest() {
        return DecisionRequest.of(STATE, "frustration", new Question.Score("How frustrated is the customer?",
                List.of("Calm", "Frustrated", "Very angry")));
    }

    private void assertSentDocumentedRequest(String fixture) {
        var sent = stub.recorded().stream().filter(r -> r.path().equals("/v1/systemone")).toList();
        assertEquals(1, sent.size());
        var request = sent.get(0);
        assertEquals("POST", request.method());
        assertEquals("Bearer test-key", request.authorization());
        assertEquals("application/json", request.contentType());
        assertEquals(TypesafeWire.MAPPER.readTree(TypesafeStub.fixture(fixture)),
                TypesafeWire.MAPPER.readTree(request.body()));
    }

    @Test
    void noulSendsTheDocumentedRequestAndDerivesConfidenceFromP() {
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));

        var result = model.decide(noulRequest());

        assertSentDocumentedRequest("request-noul.json");
        var noul = result.answer("is_urgent", Answer.Noul.class).orElseThrow();
        assertTrue(noul.value());
        assertEquals(0.95, noul.probabilityTrue().getAsDouble(), 1e-12);
        assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, noul.confidence().source());
        // The provider returns no confidence for a noul: it is |2p - 1|.
        assertEquals(0.9, noul.confidence().aggregate().getAsDouble(), 1e-12);
        assertEquals("jev-1.13.0", result.model());
        var usage = result.usage().orElseThrow();
        assertEquals(296, usage.input());
        assertEquals(20, usage.output());
    }

    @Test
    void choiceSendsTheDocumentedRequestAndKeepsTheProviderDistribution() {
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-choice.json")));

        var result = model.decide(choiceRequest());

        assertSentDocumentedRequest("request-choice.json");
        var choice = result.answer("department", Answer.Choice.class).orElseThrow();
        assertEquals("billing", choice.choice());
        assertEquals(Map.of("billing", 0.88, "technical", 0.12, "sales", 0.0), choice.probabilities());
        assertEquals(List.of("billing", "technical", "sales"), List.copyOf(choice.probabilities().keySet()));
        assertEquals(0.81, choice.confidence().aggregate().getAsDouble(), 1e-12);
        assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, choice.confidence().source());
        assertTrue(choice.confidence().decision().isEmpty());
    }

    @Test
    void scoreSendsTheDocumentedRequestAndKeepsTheProviderDistribution() {
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-score.json")));

        var result = model.decide(scoreRequest());

        assertSentDocumentedRequest("request-score.json");
        var score = result.answer("frustration", Answer.Score.class).orElseThrow();
        assertEquals(1.05, score.score(), 1e-12);
        assertEquals(Map.of(0, 0.0, 1, 0.95, 2, 0.05), score.probabilities());
        assertEquals(0.92, score.confidence().aggregate().getAsDouble(), 1e-12);
        assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, score.confidence().source());
    }

    @Test
    void severalQuestionsGoOutInOneRequestAndComeBackInRequestOrder() {
        var questions = new LinkedHashMap<String, Question>();
        questions.put("frustration", scoreRequest().questions().get("frustration"));
        questions.put("is_urgent", noulRequest().questions().get("is_urgent"));
        stub.onDecide(TypesafeStub.Reply.json(200, """
                {"model":"jev-1.13.0","answers":{
                  "is_urgent":{"type":"noul","noul":0.1},
                  "frustration":{"type":"score","score":1.0,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},
                    "probabilities":{"0":0.0,"1":1.0,"2":0.0},"confidence":1.0}},
                 "usage":{"input_tokens":10,"output_tokens":2}}"""));

        var result = model.decide(new DecisionRequest(STATE, questions, Duration.ofSeconds(5)));

        assertEquals(1, stub.hits("/v1/systemone"));
        assertEquals(List.of("frustration", "is_urgent"), List.copyOf(result.answers().keySet()));
        var noul = result.answer("is_urgent", Answer.Noul.class).orElseThrow();
        assertFalse(noul.value());
        assertEquals(0.8, noul.confidence().aggregate().getAsDouble(), 1e-12);
    }

    @Test
    void anAnswerMissingFromTheReplyFailsOnlyThatQuestion() {
        var questions = new LinkedHashMap<String, Question>();
        questions.put("is_urgent", noulRequest().questions().get("is_urgent"));
        questions.put("other", new Question.Noul("Is it about money?", null, null));
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));

        var result = model.decide(new DecisionRequest(STATE, questions, Duration.ofSeconds(5)));

        assertInstanceOf(Answer.Noul.class, result.answers().get("is_urgent"));
        var failed = assertInstanceOf(Answer.Failed.class, result.answers().get("other"));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failed.reason());
    }

    @Test
    void a200ThatIsNotJsonFailsEveryQuestionAsUnparseable() {
        stub.onDecide(TypesafeStub.Reply.json(200, "<html>gateway</html>"));

        var failed = onlyFailure(model.decide(noulRequest()), "is_urgent");

        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failed.reason());
    }

    // ---- errors -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationErrorsAreNotRetriedAndMarkTheModelUnavailable(int status) {
        assertTrue(model.isAvailable());
        assertEquals(1, stub.hits("/v1/models"));
        stub.onDecide(TypesafeStub.Reply.json(status, TypesafeStub.fixture("error-" + status + ".json")));

        var failed = onlyFailure(model.decide(noulRequest()), "is_urgent");

        assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
        assertTrue(failed.detail().contains("HTTP " + status), failed.detail());
        assertTrue(failed.detail().contains("authentication_error"), failed.detail());
        assertTrue(failed.detail().contains("request req_stub_"), failed.detail());
        assertEquals(1, stub.hits("/v1/systemone"), "an auth error is not retried");
        // The rejected key marks the model down without waiting for the TTL.
        assertFalse(model.isAvailable());
        assertEquals(1, stub.hits("/v1/models"), "the down verdict is cached, not re-probed");
    }

    @Test
    void validationErrorIsNotRetried() {
        stub.onDecide(TypesafeStub.Reply.json(422, TypesafeStub.fixture("error-422.json")));

        var failed = onlyFailure(model.decide(noulRequest()), "is_urgent");

        assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
        assertTrue(failed.detail().contains("HTTP 422 Field required"), failed.detail());
        assertEquals(1, stub.hits("/v1/systemone"));
    }

    @Test
    void rateLimitIsRetriedAfterRetryAfterMs() {
        stub.onDecide(
                TypesafeStub.Reply.json(429, TypesafeStub.fixture("error-429.json")).withHeader("retry-after-ms", "20"),
                TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));

        var result = model.decide(noulRequest());

        assertInstanceOf(Answer.Noul.class, result.answers().get("is_urgent"));
        assertEquals(2, stub.hits("/v1/systemone"));
    }

    @Test
    void overloadIsRetriedAfterRetryAfterSeconds() {
        stub.onDecide(
                TypesafeStub.Reply.json(529, TypesafeStub.fixture("error-529.json")).withHeader("Retry-After", "0"),
                TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));

        var result = model.decide(noulRequest());

        assertInstanceOf(Answer.Noul.class, result.answers().get("is_urgent"));
        assertEquals(2, stub.hits("/v1/systemone"));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 529})
    void persistentRateLimitOrOverloadIsCapacityAfterMaxRetries(int status) {
        stub.onDecide(TypesafeStub.Reply.json(status, TypesafeStub.fixture("error-" + status + ".json"))
                .withHeader("retry-after-ms", "5"));

        var failed = onlyFailure(model.decide(noulRequest()), "is_urgent");

        assertEquals(Answer.Failed.Reason.CAPACITY, failed.reason());
        assertTrue(failed.detail().contains("HTTP " + status), failed.detail());
        assertEquals(1 + TypesafeDecisionModel.DEFAULT_MAX_RETRIES, stub.hits("/v1/systemone"));
    }

    @Test
    void aRetryAfterPastTheDeadlineStopsAtOnce() {
        stub.onDecide(TypesafeStub.Reply.json(429, TypesafeStub.fixture("error-429.json"))
                .withHeader("Retry-After", "30"));
        var started = System.nanoTime();

        var result = model.decide(noulRequest().withTimeout(Duration.ofSeconds(2)));

        var failed = onlyFailure(result, "is_urgent");
        assertEquals(Answer.Failed.Reason.CAPACITY, failed.reason());
        assertTrue(failed.detail().contains("30000 ms wait"), failed.detail());
        assertEquals(1, stub.hits("/v1/systemone"));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "did not wait for the retry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry-after-ms: 1e18", "Retry-After: Fri, 31 Dec 9999 23:59:59 GMT",
        "Retry-After: 1e300"})
    void aHugeRetryAfterStopsAtOnceInsteadOfSleeping(String header) {
        var name = header.substring(0, header.indexOf(':'));
        var value = header.substring(header.indexOf(':') + 1).strip();
        stub.onDecide(TypesafeStub.Reply.json(529, TypesafeStub.fixture("error-529.json")).withHeader(name, value));
        var started = System.nanoTime();

        // Preemptive: before the fix the sum now + wait overflowed and decide slept for centuries.
        var result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> model.decide(noulRequest().withTimeout(Duration.ofSeconds(1))));

        var failed = onlyFailure(result, "is_urgent");
        assertEquals(Answer.Failed.Reason.CAPACITY, failed.reason());
        assertTrue(failed.detail().contains("would pass the deadline"), failed.detail());
        assertEquals(1, stub.hits("/v1/systemone"));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1), "did not wait for the retry");
    }

    @Test
    void serverErrorWithoutRetryAfterIsRetriedWithBackoff() {
        stub.onDecide(
                TypesafeStub.Reply.json(503, "{\"detail\":\"unavailable\"}"),
                TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));

        var result = model.decide(noulRequest());

        assertInstanceOf(Answer.Noul.class, result.answers().get("is_urgent"));
        assertEquals(2, stub.hits("/v1/systemone"));
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        try (var once = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl())
                .maxRetries(0).build()) {
            stub.onDecide(TypesafeStub.Reply.json(529, TypesafeStub.fixture("error-529.json")));

            var failed = onlyFailure(once.decide(noulRequest()), "is_urgent");

            assertEquals(Answer.Failed.Reason.CAPACITY, failed.reason());
            assertEquals(1, stub.hits("/v1/systemone"));
        }
    }

    @Test
    void aDroppedConnectionIsRetried() {
        stub.onDecide(TypesafeStub.Reply.dropConnection(),
                TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));

        var result = model.decide(noulRequest());

        assertInstanceOf(Answer.Noul.class, result.answers().get("is_urgent"));
        assertEquals(2, stub.hits("/v1/systemone"), "one dropped attempt, one retry");
    }

    @Test
    void aDroppedConnectionWithZeroRetriesIsOneAttemptAndAnError() {
        stub.onDecide(TypesafeStub.Reply.dropConnection(),
                TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")));
        try (var once = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl())
                .maxRetries(0).build()) {

            var failed = onlyFailure(once.decide(noulRequest()), "is_urgent");

            assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
            assertTrue(failed.detail().contains("connection failed on attempt 1 of 1"), failed.detail());
            assertEquals(1, stub.hits("/v1/systemone"));
        }
    }

    @Test
    void aConnectTimeoutIsARetriedConnectionErrorNotADeadline() throws Exception {
        try (var blackhole = new Blackhole();
             var slow = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(blackhole.baseUrl())
                     .connectTimeout(Duration.ofMillis(200)).build()) {
            var started = System.nanoTime();

            var failed = onlyFailure(slow.decide(noulRequest().withTimeout(Duration.ofSeconds(20))), "is_urgent");

            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            // Before the fix: TIMEOUT "no reply within 20000 ms" after one 200 ms attempt.
            assertEquals(Answer.Failed.Reason.ERROR, failed.reason(), failed.detail());
            assertTrue(failed.detail().contains("HttpConnectTimeoutException"), failed.detail());
            assertTrue(failed.detail().contains("on attempt 3 of 3"), failed.detail());
            assertTrue(elapsed.compareTo(Duration.ofSeconds(15)) < 0, "returned after " + elapsed);
        }
    }

    @Test
    void aConnectTimeoutWithZeroRetriesIsOneAttempt() throws Exception {
        try (var blackhole = new Blackhole();
             var once = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(blackhole.baseUrl())
                     .connectTimeout(Duration.ofMillis(200)).maxRetries(0).build()) {

            var failed = onlyFailure(once.decide(noulRequest().withTimeout(Duration.ofSeconds(20))), "is_urgent");

            assertEquals(Answer.Failed.Reason.ERROR, failed.reason(), failed.detail());
            assertTrue(failed.detail().contains("on attempt 1 of 1"), failed.detail());
        }
    }

    @Test
    void aReplyPastTheDeadlineIsTimeoutAndDecideReturnsOnTime() {
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")).delayed(3_000));
        var started = System.nanoTime();

        var result = model.decide(noulRequest().withTimeout(Duration.ofMillis(300)));

        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        var failed = onlyFailure(result, "is_urgent");
        assertEquals(Answer.Failed.Reason.TIMEOUT, failed.reason());
        assertTrue(failed.detail().contains("300 ms deadline"), failed.detail());
        assertTrue(elapsed.compareTo(Duration.ofMillis(1_500)) < 0, "returned after " + elapsed);
    }

    @Test
    void anOversizedBodyIsUnparseableNotBuffered() {
        var huge = new byte[TypesafeDecisionModel.MAX_RESPONSE_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        stub.onDecide(new TypesafeStub.Reply(200, Map.of(), huge, 0));

        var failed = onlyFailure(model.decide(noulRequest()), "is_urgent");

        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failed.reason());
        assertTrue(failed.detail().contains("exceeds"), failed.detail());
    }

    @Test
    void noFreeSlotBeforeTheDeadlineIsCapacity() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")))
                .beforeDecideReply(() -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        try (var single = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl())
                .maxConcurrency(1).build()) {
            var first = Thread.ofVirtual().start(() -> single.decide(noulRequest()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var second = single.decide(noulRequest().withTimeout(Duration.ofMillis(200)));

            assertEquals(Answer.Failed.Reason.CAPACITY, onlyFailure(second, "is_urgent").reason());
            release.countDown();
            first.join(5_000);
        }
    }

    // ---- availability --------------------------------------------------------

    @Test
    void noKeyIsUnavailableWithoutAProbe() {
        try (var keyless = TypesafeDecisionModel.builder().baseUrl(stub.baseUrl()).build()) {
            assertFalse(keyless.isAvailable());
            assertEquals(0, stub.hits("/v1/models"));
            var failed = onlyFailure(keyless.decide(noulRequest()), "is_urgent");
            assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
            assertEquals(0, stub.hits("/v1/systemone"));
        }
    }

    @Test
    void availabilityIsProvenByModelsAndCachedForTheTtl() {
        var now = new AtomicLong();
        try (var timed = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl())
                .nanoClock(now::get).build()) {
            assertTrue(timed.isAvailable());
            var probe = stub.recorded().get(0);
            assertEquals("GET", probe.method());
            assertEquals("Bearer test-key", probe.authorization());
            assertTrue(timed.isAvailable());
            assertEquals(1, stub.hits("/v1/models"), "cached within the TTL");

            stub.onModels(TypesafeStub.Reply.json(401, TypesafeStub.fixture("error-401.json")));
            now.addAndGet(Duration.ofSeconds(TypesafeDecisionModel.DEFAULT_AVAILABLE_TTL_SECONDS).toNanos());
            assertFalse(timed.isAvailable(), "re-probed after the TTL and the key is now rejected");
            assertEquals(2, stub.hits("/v1/models"));

            stub.onModels(TypesafeStub.Reply.json(200, TypesafeStub.fixture("models.json")));
            assertFalse(timed.isAvailable(), "the down verdict is cached too");
            now.addAndGet(Duration.ofSeconds(TypesafeDecisionModel.DEFAULT_UNAVAILABLE_TTL_SECONDS).toNanos());
            assertTrue(timed.isAvailable());
            assertEquals(3, stub.hits("/v1/models"));
        }
    }

    @Test
    void instancesOfOneConfigurationShareOneVerdictAndOneProbe() {
        stub.onModels(TypesafeStub.Reply.json(401, TypesafeStub.fixture("error-401.json")));
        var instances = new ArrayList<TypesafeDecisionModel>();
        try {
            for (var i = 0; i < 10; i++) {
                var fresh = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl()).build();
                instances.add(fresh);
                assertFalse(fresh.isAvailable());
            }
            assertEquals(1, stub.hits("/v1/models"), "a fresh instance reuses the cached down verdict");
            assertTrue(instances.get(0).hasClient(), "the probing instance created its client");
            assertTrue(instances.stream().skip(1).noneMatch(TypesafeDecisionModel::hasClient),
                    "an instance that only read the verdict never created an HttpClient");

            // Another key is another configuration: it probes on its own.
            try (var other = TypesafeDecisionModel.builder().apiKey("other-key").baseUrl(stub.baseUrl()).build()) {
                assertFalse(other.isAvailable());
                assertEquals(2, stub.hits("/v1/models"));
            }
        } finally {
            instances.forEach(TypesafeDecisionModel::close);
        }
    }

    @Test
    void anInterruptedCallerDoesNotMarkTheConfigurationDownForOthers() throws Exception {
        var interruptedSaw = new AtomicReference<Boolean>();
        var flagKept = new AtomicReference<Boolean>();
        try (var a = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl()).build();
             var b = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl()).build()) {
            var caller = new Thread(() -> {
                Thread.currentThread().interrupt();
                interruptedSaw.set(a.isAvailable());
                flagKept.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(caller.isAlive());
            assertFalse(interruptedSaw.get(), "the interrupted caller gets no positive verdict");
            assertTrue(flagKept.get(), "the interrupt flag is restored");

            var before = stub.hits("/v1/models");
            assertTrue(b.isAvailable(), "a clean caller of the same configuration is not handed a down verdict");
            // ">" not "==": the aborted request may still land on the stub afterwards.
            assertTrue(stub.hits("/v1/models") > before, "the clean caller's probe reached /v1/models");
            assertTrue(a.isAvailable(), "the shared verdict is the endpoint's, so the first instance sees it too");
        }
    }

    @Test
    void aVeryLongTtlIsTrustedWithoutOverflowing() {
        // Duration.ofDays(200_000).toNanos() overflows a long.
        try (var forever = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl())
                .availableTtl(Duration.ofDays(200_000)).unavailableTtl(Duration.ofDays(200_000)).build()) {
            assertTrue(forever.isAvailable());
            assertTrue(forever.isAvailable(), "a cached verdict is read, not an ArithmeticException");
            assertEquals(1, stub.hits("/v1/models"));
        }
        TypesafeDecisionModel.forgetSharedVerdicts();
        stub.onModels(TypesafeStub.Reply.json(401, TypesafeStub.fixture("error-401.json")));
        try (var down = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(stub.baseUrl())
                .unavailableTtl(Duration.ofDays(200_000)).build()) {
            assertFalse(down.isAvailable());
            assertFalse(down.isAvailable());
            assertEquals(2, stub.hits("/v1/models"));
        }
    }

    @Test
    void theAvailabilityProbeDoesNotPinAVirtualThreadCarrier() throws Exception {
        stub.onModels(TypesafeStub.Reply.json(200, TypesafeStub.fixture("models.json")).delayed(300));
        var dump = Files.createTempFile("typesafe-pinning", ".jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            recording.start();
            var probing = Thread.ofVirtual().start(() -> assertTrue(model.isAvailable()));
            probing.join(10_000);
            assertFalse(probing.isAlive());
            recording.stop();
            recording.dump(dump);
            var pinnedHere = RecordingFile.readAllEvents(dump).stream()
                    .filter(e -> e.getStackTrace() != null && e.getStackTrace().getFrames().stream()
                            .anyMatch(f -> f.getMethod().getType().getName()
                                    .equals(TypesafeDecisionModel.class.getName())))
                    .toList();
            assertEquals(List.of(), pinnedHere, "the probe parked a virtual thread inside a monitor");
            assertEquals(1, stub.hits("/v1/models"));
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    @Test
    void theSharedVerdictsAreBounded() {
        var instances = new ArrayList<TypesafeDecisionModel>();
        try {
            for (var i = 0; i < TypesafeDecisionModel.MAX_SHARED_VERDICTS + 10; i++) {
                instances.add(TypesafeDecisionModel.builder().apiKey("key-" + i).baseUrl(stub.baseUrl()).build());
            }
            assertEquals(TypesafeDecisionModel.MAX_SHARED_VERDICTS, TypesafeDecisionModel.sharedVerdictCount());
        } finally {
            instances.forEach(TypesafeDecisionModel::close);
        }
    }

    @Test
    void theLoggedOnceMessagesAreBounded() {
        TypesafeDecisionModel.forgetLoggedOnce();
        for (var i = 0; i < TypesafeDecisionModel.MAX_LOGGED_ONCE + 10; i++) {
            assertTrue(TypesafeDecisionModel.firstTime("config:error " + i));
        }
        assertEquals(TypesafeDecisionModel.MAX_LOGGED_ONCE, TypesafeDecisionModel.loggedOnceCount());
        assertFalse(TypesafeDecisionModel.firstTime("config:error " + (TypesafeDecisionModel.MAX_LOGGED_ONCE + 9)),
                "a remembered message is not logged again");
        TypesafeDecisionModel.forgetLoggedOnce();
    }

    @Test
    void theHttpClientIsCreatedOnFirstUseAndReleasedByClose() {
        assertFalse(model.hasClient());
        assertTrue(model.isAvailable());
        assertTrue(model.hasClient());
        model.close();
        assertFalse(model.hasClient());
    }

    @Test
    void aModelsReplyWithoutTheDocumentedBodyIsUnavailable() {
        stub.onModels(TypesafeStub.Reply.json(200, "{\"data\":[]}"));
        assertFalse(model.isAvailable());
    }

    @Test
    void anUnreachableEndpointIsUnavailable() {
        var port = stub.baseUrl();
        stub.close();
        try (var dead = TypesafeDecisionModel.builder().apiKey("test-key").baseUrl(port).build()) {
            assertFalse(dead.isAvailable());
            var failed = onlyFailure(dead.decide(noulRequest().withTimeout(Duration.ofSeconds(3))), "is_urgent");
            assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
            assertTrue(failed.detail().contains("connection failed"), failed.detail());
        }
        stub = new TypesafeStub();
    }

    // ---- lifecycle -----------------------------------------------------------

    @Test
    void closeIsIdempotentAndStopsAllTraffic() {
        assertTrue(model.isAvailable());
        model.close();
        model.close();

        assertFalse(model.isAvailable());
        var failed = onlyFailure(model.decide(noulRequest()), "is_urgent");
        assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
        assertTrue(failed.detail().contains("closed"), failed.detail());
        assertEquals(0, stub.hits("/v1/systemone"));
        assertEquals(1, stub.hits("/v1/models"));
    }

    @Test
    void closeAbortsAnInFlightRequestInsteadOfWaitingForIt() throws Exception {
        var entered = new CountDownLatch(1);
        stub.onDecide(TypesafeStub.Reply.json(200, TypesafeStub.fixture("response-noul.json")).delayed(20_000))
                .beforeDecideReply(entered::countDown);
        var result = new AtomicReference<DecisionResult>();
        var deciding = Thread.ofVirtual().start(() ->
                result.set(model.decide(noulRequest().withTimeout(Duration.ofSeconds(15)))));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var started = System.nanoTime();

        model.close();

        var closeTook = Duration.ofNanos(System.nanoTime() - started);
        // Before the fix close() waited for the exchange: about 15 s here.
        assertTrue(closeTook.compareTo(Duration.ofSeconds(3)) < 0, "close() took " + closeTook);
        deciding.join(5_000);
        assertFalse(deciding.isAlive(), "the in-flight decide returned once its exchange was aborted");
        var failed = onlyFailure(result.get(), "is_urgent");
        assertEquals(Answer.Failed.Reason.ERROR, failed.reason(), failed.detail());
        assertTrue(failed.detail().contains("closed while the request was in flight"), failed.detail());
        assertEquals(1, stub.hits("/v1/systemone"), "an aborted exchange is not retried");
    }

    /**
     * A loopback listener whose accept queue is full and never drained, so a
     * new TCP connection to it is never established: the client's connect
     * timeout fires. The queue is filled with plain sockets until one of them
     * cannot connect.
     */
    private static final class Blackhole implements AutoCloseable {
        private static final InetAddress LOOPBACK = loopback();

        private final ServerSocket server;
        private final List<Socket> fillers = new ArrayList<>();

        Blackhole() throws IOException {
            server = new ServerSocket(0, 1, LOOPBACK);
            var full = false;
            for (var i = 0; i < 256 && !full; i++) {
                var socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(LOOPBACK, server.getLocalPort()), 200);
                    fillers.add(socket);
                } catch (SocketTimeoutException e) {
                    socket.close();
                    full = true;
                }
            }
            if (!full) {
                close();
                throw new IllegalStateException("could not fill the accept queue of a loopback listener");
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private static InetAddress loopback() {
            try {
                return InetAddress.getByName("127.0.0.1");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() throws IOException {
            for (var socket : fillers) {
                socket.close();
            }
            server.close();
        }
    }

    private static Answer.Failed onlyFailure(DecisionResult result, String id) {
        assertEquals(1, result.answers().size());
        return assertInstanceOf(Answer.Failed.class, result.answers().get(id));
    }
}
