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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decorator that turns the turn's {@link AiConfidence} into a
 * {@link ConfidenceRoute} — see {@link ConfidenceRouting}.
 *
 * <p>Sits inside {@link ConfidenceCapturingSession}, so it observes every
 * confidence source: a runtime's direct {@code LOGPROBS_NATIVE} call passes
 * through the capturing layer to here, and the capturing layer's parsed
 * model-reported value arrives from its {@code complete()} before it
 * completes this session. The last signal observed wins.</p>
 *
 * <p>Routing happens once, on the first {@code complete()}; a turn that
 * errored or was already closed (a guardrail block) is not routed.</p>
 */
class ConfidenceRoutingSession extends DelegatingStreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(ConfidenceRoutingSession.class);

    private final ConfidenceRouting routing;
    private final AiRequest request;
    private final AtomicBoolean routed = new AtomicBoolean();
    private volatile AiConfidence observed;

    ConfidenceRoutingSession(StreamingSession delegate, ConfidenceRouting routing, AiRequest request) {
        super(delegate);
        this.routing = Objects.requireNonNull(routing, "routing");
        this.request = request != null ? request : new AiRequest("");
    }

    @Override
    public void confidence(AiConfidence confidence) {
        if (confidence != null) {
            observed = confidence;
        }
        delegate.confidence(confidence);
    }

    @Override
    public void complete() {
        route();
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        route();
        delegate.complete(summary);
    }

    private void route() {
        if (delegate.isClosed() || delegate.hasErrored() || !routed.compareAndSet(false, true)) {
            return;
        }
        var confidence = observed;
        var route = routing.route(confidence);
        var reported = confidence != null
                ? confidence : AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD);
        delegate.sendMetadata(ConfidenceRouting.ROUTE_METADATA_KEY, route.name());
        var handler = routing.onDecision();
        if (handler != null) {
            try {
                handler.accept(new ConfidenceDecision(route, reported, request));
            } catch (RuntimeException e) {
                // A failing handler must not take down a turn that already
                // produced its answer; the wire signal above is still out.
                logger.warn("ConfidenceRouting handler failed for route {}", route, e);
            }
        }
    }
}
