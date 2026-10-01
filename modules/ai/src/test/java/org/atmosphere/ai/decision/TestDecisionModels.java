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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.DecisionDistribution;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * {@link DecisionModel} registrations for tests, listed in
 * {@code src/test/resources/META-INF/services}. Every one is <em>unavailable</em>
 * until a test switches it on, so the rest of the suite resolves exactly as it
 * would without them; tests that switch one on call {@link #reset()} afterwards.
 */
public final class TestDecisionModels {

    private TestDecisionModels() {
    }

    /** Switch every registration off and restore the default answers. */
    public static void reset() {
        Preferred.available = false;
        Preferred.behaviour = TestDecisionModels::answerFalse;
        Lower.available = false;
        Throwing.throwing = false;
        Closeable.available = false;
        Closeable.CLOSED.set(0);
        DecisionModelResolver.reset();
    }

    /**
     * A {@link DecisionModel} that answers every question as a {@link Answer.Noul}
     * with the given measured {@code P(true)}.
     */
    public static DecisionResult measured(DecisionRequest request, double probabilityTrue) {
        var answers = new LinkedHashMap<String, Answer>();
        for (var id : request.questions().keySet()) {
            var distribution = new DecisionDistribution("answer",
                    Map.of("true", probabilityTrue, "false", 1.0 - probabilityTrue), 1.0);
            answers.put(id, new Answer.Noul(id, probabilityTrue >= 0.5, OptionalDouble.of(probabilityTrue),
                    AiConfidence.fromDecision(distribution, List.of())));
        }
        return new DecisionResult("test", answers, Optional.empty(), Duration.ZERO);
    }

    private static DecisionResult answerFalse(DecisionRequest request) {
        return measured(request, 0.0);
    }

    /** Highest priority; its answers are scripted through {@link #behaviour}. */
    public static final class Preferred implements DecisionModel {
        public static volatile boolean available;
        public static volatile Function<DecisionRequest, DecisionResult> behaviour =
                TestDecisionModels::answerFalse;

        @Override
        public String name() {
            return "test-preferred";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public DecisionResult decide(DecisionRequest request) {
            return behaviour.apply(request);
        }
    }

    /** Lower priority, never picked while {@link Preferred} is available. */
    public static final class Lower implements DecisionModel {
        public static volatile boolean available;

        @Override
        public String name() {
            return "test-lower";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int priority() {
            return 1;
        }

        @Override
        public DecisionResult decide(DecisionRequest request) {
            return answerFalse(request);
        }
    }

    /** Holds a resource: counts how many instances were closed. Ranks between Lower and Preferred. */
    public static final class Closeable implements DecisionModel, AutoCloseable {
        public static volatile boolean available;
        public static final AtomicInteger CLOSED = new AtomicInteger();

        @Override
        public String name() {
            return "test-closeable";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int priority() {
            return 5;
        }

        @Override
        public DecisionResult decide(DecisionRequest request) {
            return answerFalse(request);
        }

        @Override
        public void close() {
            CLOSED.incrementAndGet();
        }
    }

    /** Highest priority of all, but its availability check can throw. */
    public static final class Throwing implements DecisionModel {
        public static volatile boolean throwing;

        @Override
        public String name() {
            return "test-throwing";
        }

        @Override
        public boolean isAvailable() {
            if (throwing) {
                throw new IllegalStateException("availability probe failed");
            }
            return false;
        }

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public DecisionResult decide(DecisionRequest request) {
            throw new UnsupportedOperationException("never available");
        }
    }
}
