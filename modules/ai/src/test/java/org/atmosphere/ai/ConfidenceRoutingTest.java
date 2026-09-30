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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfidenceRouting} tiers and the {@link ConfidenceRoutingSession}
 * layer, driven through the real {@link DispatchDecorators} composer so the
 * behavior is identical on the {@code @AiEndpoint} and pipeline paths.
 */
class ConfidenceRoutingTest {

    // --- tiers -------------------------------------------------------------

    @Test
    void defaultTiersRouteOnInclusiveThresholds() {
        var routing = ConfidenceRouting.defaults();
        assertEquals(ConfidenceRoute.ACT, routing.route(AiConfidence.reported(1.0)));
        assertEquals(ConfidenceRoute.ACT, routing.route(AiConfidence.reported(0.9)));
        assertEquals(ConfidenceRoute.CONFIRM, routing.route(AiConfidence.reported(0.89)));
        assertEquals(ConfidenceRoute.CONFIRM, routing.route(AiConfidence.reported(0.5)));
        assertEquals(ConfidenceRoute.ESCALATE, routing.route(AiConfidence.reported(0.49)));
        assertEquals(ConfidenceRoute.ESCALATE, routing.route(AiConfidence.reported(0.0)));
    }

    @Test
    void unknownFailsClosedByDefault() {
        var routing = ConfidenceRouting.defaults();
        assertEquals(ConfidenceRoute.ESCALATE, routing.route(null));
        assertEquals(ConfidenceRoute.ESCALATE,
                routing.route(AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD)));
        assertEquals(ConfidenceRoute.ESCALATE,
                routing.route(AiConfidence.fromLogprobs(List.of())));
    }

    @Test
    void unknownRouteIsAnExplicitOptIn() {
        var routing = ConfidenceRouting.defaults().withUnknownRoute(ConfidenceRoute.CONFIRM);
        assertEquals(ConfidenceRoute.CONFIRM, routing.route(null));
    }

    @Test
    void logprobsSourceIsRoutedLikeAnyOther() {
        var confident = AiConfidence.fromLogprobs(List.of(
                new TokenLogprob("yes", Math.log(0.99)), new TokenLogprob(".", Math.log(0.97))));
        assertEquals(ConfidenceRoute.ACT, ConfidenceRouting.defaults().route(confident));
    }

    @Test
    void thresholdsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> ConfidenceRouting.of(0.5, 0.9));
        assertThrows(IllegalArgumentException.class, () -> ConfidenceRouting.of(1.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> ConfidenceRouting.of(0.9, -0.1));
        assertThrows(IllegalArgumentException.class, () -> ConfidenceRouting.of(Double.NaN, 0.5));
        assertThrows(NullPointerException.class,
                () -> new ConfidenceRouting(0.9, 0.5, null, null));
    }

    @Test
    void fromMetadata() {
        var routing = ConfidenceRouting.of(0.8, 0.4);
        assertSame(routing, ConfidenceRouting.from(Map.of(ConfidenceRouting.METADATA_KEY, routing)));
        assertNull(ConfidenceRouting.from(Map.of(ConfidenceRouting.METADATA_KEY, "nope")));
        assertNull(ConfidenceRouting.from(null));
    }

    // --- the layer, through the composer -------------------------------------

    private static DispatchDecorators.Composed compose(RecordingSession base, Class<?> responseType,
                                                       AiConfidenceElicitation elicitation,
                                                       ConfidenceRouting routing) {
        return DispatchDecorators.compose(base, new DispatchDecorators.Spec(
                null, "client-1", "hello",
                "user-1", "agent-1", "conv-1",
                AiMetrics.NOOP, "test-model", "test-runtime", "test-model",
                null, List.of(), responseType, elicitation, new AiRequest("hello"), routing));
    }

    @Test
    void modelReportedConfidenceIsRoutedBeforeTheTerminalFrame() {
        var decisions = new ArrayList<ConfidenceDecision>();
        var base = new RecordingSession();
        var target = compose(base, null, null,
                ConfidenceRouting.defaults().withHandler(decisions::add)).target();

        target.send("Paris is the capital of France. {\"confidence\": 0.95}");
        target.complete();

        assertEquals(1, decisions.size());
        assertEquals(ConfidenceRoute.ACT, decisions.get(0).route());
        assertEquals(0.95, decisions.get(0).confidence().aggregate().getAsDouble());
        assertEquals("hello", decisions.get(0).request().message());
        var route = base.events.indexOf("meta:ai.confidence.route=ACT");
        assertTrue(route >= 0, base.events.toString());
        assertTrue(route < base.events.indexOf("complete"), "route must precede the terminal frame");
    }

    @Test
    void routingWithoutElicitationInstallsTheDefaultCue() {
        var composed = compose(new RecordingSession(), null, null, ConfidenceRouting.defaults());
        assertEquals(AiConfidenceElicitation.defaults().effectiveCue(), composed.confidenceCueText(),
                "without a cue the model is never asked, and every turn would escalate");
    }

    @Test
    void missingFieldEscalates() {
        var base = new RecordingSession();
        var target = compose(base, null, null, ConfidenceRouting.defaults()).target();

        target.send("I think so, maybe.");
        target.complete();

        assertTrue(base.events.contains("meta:ai.confidence.route=ESCALATE"), base.events.toString());
    }

    @Test
    void nativeLogprobsWinOverTheParsedField() {
        var decisions = new ArrayList<ConfidenceDecision>();
        var base = new RecordingSession();
        var target = compose(base, null, AiConfidenceElicitation.defaults(),
                ConfidenceRouting.defaults().withHandler(decisions::add)).target();

        target.send("Maybe. {\"confidence\": 0.99}");
        // The runtime reports native logprobs: the capturing layer then skips its parse.
        target.confidence(AiConfidence.fromLogprobs(List.of(new TokenLogprob("Maybe", Math.log(0.3)))));
        target.complete();

        assertEquals(1, decisions.size());
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, decisions.get(0).confidence().source());
        assertEquals(ConfidenceRoute.ESCALATE, decisions.get(0).route());
    }

    @Test
    void structuredOutputReadsTheRecordFieldWithoutAppendingTheCue() {
        record Verdict(String label, double confidence) { }
        var decisions = new ArrayList<ConfidenceDecision>();
        var base = new RecordingSession();
        var composed = compose(base, Verdict.class, AiConfidenceElicitation.defaults(),
                ConfidenceRouting.defaults().withHandler(decisions::add));

        assertNull(composed.confidenceCueText(), "the cue would break the single-JSON-object parse");
        assertEquals(List.of("structured-output", "confidence-routing", "confidence"), composed.layers());

        composed.target().send("{\"label\": \"refund\", \"confidence\": 0.7}");
        composed.target().complete();

        assertEquals(1, decisions.size());
        assertEquals(ConfidenceRoute.CONFIRM, decisions.get(0).route());
    }

    @Test
    void erroredTurnIsNotRouted() {
        var decisions = new ArrayList<ConfidenceDecision>();
        var base = new RecordingSession();
        var target = compose(base, null, null,
                ConfidenceRouting.defaults().withHandler(decisions::add)).target();

        target.send("partial {\"confidence\": 0.99}");
        target.error(new IllegalStateException("provider dropped the stream"));
        target.complete();

        assertTrue(decisions.isEmpty(), "there is no answer to act on");
        assertFalse(base.events.stream().anyMatch(e -> e.startsWith("meta:ai.confidence.route")));
    }

    @Test
    void routesOnceAcrossRepeatedCompletes() {
        var decisions = new ArrayList<ConfidenceDecision>();
        var base = new RecordingSession();
        base.closeOnComplete = false;
        var target = compose(base, null, null,
                ConfidenceRouting.defaults().withHandler(decisions::add)).target();

        target.send("{\"confidence\": 0.6}");
        target.complete();
        target.complete("again");

        assertEquals(1, decisions.size());
    }

    @Test
    void failingHandlerDoesNotBreakTheTurn() {
        var base = new RecordingSession();
        var target = compose(base, null, null, ConfidenceRouting.defaults().withHandler(d -> {
            throw new IllegalStateException("review queue down");
        })).target();

        target.send("{\"confidence\": 0.2}");
        target.complete();

        assertTrue(base.events.contains("meta:ai.confidence.route=ESCALATE"));
        assertTrue(base.events.contains("complete"), "the answer still completes");
    }

    /** Records the ordered wire events the layers produce. */
    private static final class RecordingSession implements StreamingSession {
        final List<String> events = new ArrayList<>();
        boolean closeOnComplete = true;
        private boolean closed;
        private boolean errored;

        @Override public String sessionId() { return "s-1"; }
        @Override public void send(String text) { events.add("send:" + text); }
        @Override public void sendMetadata(String key, Object value) { events.add("meta:" + key + "=" + value); }
        @Override public void progress(String message) { }
        @Override public void complete() {
            events.add("complete");
            closed = closeOnComplete;
        }
        @Override public void complete(String summary) { complete(); }
        @Override public void error(Throwable t) {
            events.add("error");
            closed = true;
            errored = true;
        }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return errored; }
    }
}
