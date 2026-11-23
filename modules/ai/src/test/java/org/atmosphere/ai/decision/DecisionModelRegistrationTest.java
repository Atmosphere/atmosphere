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

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model an application registers (a Spring bean, say) reaches that
 * application's resolution, and only that application's: the resolver used to
 * find models through {@link java.util.ServiceLoader} only, so a model defined
 * as a bean was never asked.
 */
class DecisionModelRegistrationTest {

    private AiEnvironment first;
    private AiEnvironment second;

    @BeforeEach
    void setUp() {
        TestDecisionModels.reset();
        first = AiEnvironment.create(key -> null);
        second = AiEnvironment.create(key -> null);
        first.configure("remote", "model-first", "key-first", "http://127.0.0.1:9/first/v1");
        second.configure("remote", "model-second", "key-second", "http://127.0.0.1:9/second/v1");
    }

    @AfterEach
    void tearDown() {
        second.close();
        first.close();
        TestDecisionModels.reset();
        AgentRuntimeResolver.reset();
    }

    @Test
    void aRegisteredModelAnswersForItsApplicationOnly() {
        var model = new Owned("bean", 0);
        var registration = DecisionModelResolver.register(first, model);
        try {
            assertSame(model, DecisionModelResolver.resolve(first).orElseThrow());
            assertInstanceOf(RuntimeDecisionModel.class, DecisionModelResolver.resolve(second).orElseThrow(),
                    "another application keeps its own resolution");
        } finally {
            registration.close();
        }
    }

    @Test
    void aSafetyTierThatResolvesPerCheckAsksTheRegisteredModel() {
        var model = new Owned("bean", 0);
        var registration = DecisionModelResolver.register(first, model);
        try {
            org.atmosphere.ai.guardrails.LlmModerationDetector.forEnvironment(first).detect("some reply text");
            assertTrue(model.asked.get() > 0, "the moderation tier's question reached the registered model");
        } finally {
            registration.close();
        }
    }

    @Test
    void aRegisteredModelOutranksALowerServiceLoaderOneAndWinsATie() {
        TestDecisionModels.Preferred.available = true;
        var lower = new Owned("lower", 9);
        var registration = DecisionModelResolver.register(first, lower);
        try {
            assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve(first).orElseThrow(),
                    "a ServiceLoader model with a higher priority still wins");
        } finally {
            registration.close();
        }
        var tie = new Owned("tie", 10);
        var tieRegistration = DecisionModelResolver.register(first, tie);
        try {
            assertSame(tie, DecisionModelResolver.resolve(first).orElseThrow(),
                    "on a tie the application's own model wins");
        } finally {
            tieRegistration.close();
        }
    }

    @Test
    void closingTheRegistrationRestoresTheResolutionWithoutClosingTheModel() {
        var model = new Owned("bean", 0);
        var registration = DecisionModelResolver.register(first, model);
        assertSame(model, DecisionModelResolver.resolve(first).orElseThrow());

        registration.close();
        registration.close();

        assertInstanceOf(RuntimeDecisionModel.class, DecisionModelResolver.resolve(first).orElseThrow());
        assertEquals(0, model.closed.get(), "the model is its owner's to close");
    }

    @Test
    void resetKeepsTheRegistrationAndNeverClosesTheModel() {
        var model = new Owned("bean", 0);
        var registration = DecisionModelResolver.register(first, model);
        try {
            assertSame(model, DecisionModelResolver.resolve(first).orElseThrow());
            DecisionModelResolver.reset();
            assertEquals(0, model.closed.get(), "reset closes only what the resolver created");
            assertSame(model, DecisionModelResolver.resolve(first).orElseThrow(), "the registration survives reset");
        } finally {
            registration.close();
        }
    }

    @Test
    void aDisplacedServiceLoaderModelStaysOpenUntilResetForTheConsumersThatKeptIt() {
        TestDecisionModels.Closeable.available = true;
        var kept = DecisionModelResolver.resolve(first).orElseThrow();
        assertInstanceOf(TestDecisionModels.Closeable.class, kept);
        var model = new Owned("bean", 50);
        var registration = DecisionModelResolver.register(first, model);
        try {
            assertSame(model, DecisionModelResolver.resolve(first).orElseThrow());
            // The rescan built a fresh instance and did not select it: closed.
            // The one a consumer may still hold (the injection tier keeps its
            // model) stays open.
            assertEquals(1, TestDecisionModels.Closeable.CLOSED.get());
            assertEquals(1, DecisionModelResolver.retiredCount(first));
            DecisionModelResolver.reset();
            assertEquals(2, TestDecisionModels.Closeable.CLOSED.get(), "reset closes the displaced model");
            assertEquals(0, model.closed.get(), "and never the registered one");
        } finally {
            registration.close();
        }
    }

    @Test
    void closingAnEnvironmentWithSeveralRegisteredModelsClosesNoneOfThem() {
        var environment = AiEnvironment.create(key -> null);
        environment.configure("remote", "model-x", "key-x", "http://127.0.0.1:9/x/v1");
        var lower = new Owned("lower", 1);
        var higher = new Owned("higher", 2);
        DecisionModelResolver.register(environment, lower);
        DecisionModelResolver.register(environment, higher);
        assertSame(higher, DecisionModelResolver.resolve(environment).orElseThrow());

        environment.close();
        DecisionModelResolver.resolve(first);
        DecisionModelResolver.reset();

        assertEquals(0, lower.closed.get());
        assertEquals(0, higher.closed.get());
    }

    @Test
    void repeatedRegistrationsAddOneCloseActionAndKeepTheDisplacedModelsBounded() {
        TestDecisionModels.Closeable.available = true;
        for (var i = 0; i < DecisionModelResolver.MAX_RETIRED + 5; i++) {
            DecisionModelResolver.resolve(first).orElseThrow();
            var registration = DecisionModelResolver.register(first, new Owned("bean-" + i, 50));
            DecisionModelResolver.resolve(first).orElseThrow();
            registration.close();
        }
        assertTrue(DecisionModelResolver.retiredCount(first) <= DecisionModelResolver.MAX_RETIRED,
                "retired: " + DecisionModelResolver.retiredCount(first));
    }

    @Test
    void anUnavailableRegisteredModelIsSkipped() {
        var model = new Owned("bean", 50);
        model.available = false;
        var registration = DecisionModelResolver.register(first, model);
        try {
            assertInstanceOf(RuntimeDecisionModel.class, DecisionModelResolver.resolve(first).orElseThrow());
        } finally {
            registration.close();
        }
    }

    @Test
    void theEnvironmentClosingDropsItsRegistrations() {
        var environment = AiEnvironment.create(key -> null);
        environment.configure("remote", "model-x", "key-x", "http://127.0.0.1:9/x/v1");
        var model = new Owned("bean", 0);
        DecisionModelResolver.register(environment, model);
        assertSame(model, DecisionModelResolver.resolve(environment).orElseThrow());

        environment.close();

        assertFalse(DecisionModelResolver.resolve(environment).isPresent());
        assertEquals(0, DecisionModelResolver.registrationCount(environment), "the registration left with it");
        assertEquals(0, model.closed.get(), "the model stays its owner's");
    }

    @Test
    void registrationsAreBounded() {
        var registrations = new ArrayList<DecisionModelResolver.Registration>();
        try {
            for (var i = 0; i < DecisionModelResolver.MAX_REGISTRATIONS; i++) {
                registrations.add(DecisionModelResolver.register(first, new Owned("bean-" + i, 0)));
            }
            assertThrows(IllegalStateException.class,
                    () -> DecisionModelResolver.register(first, new Owned("one-too-many", 0)));
        } finally {
            registrations.forEach(DecisionModelResolver.Registration::close);
        }
    }

    @Test
    void theSameModelTwiceOrAClosedEnvironmentIsRefused() {
        var model = new Owned("bean", 0);
        var registration = DecisionModelResolver.register(first, model);
        try {
            assertThrows(IllegalArgumentException.class, () -> DecisionModelResolver.register(first, model));
        } finally {
            registration.close();
        }
        var closed = AiEnvironment.create(key -> null);
        closed.close();
        assertThrows(IllegalStateException.class, () -> DecisionModelResolver.register(closed, new Owned("x", 0)));
    }

    @Test
    void twoApplicationsCanRegisterTheirOwnModels() {
        var inFirst = new Owned("first", 0);
        var inSecond = new Owned("second", 0);
        var a = DecisionModelResolver.register(first, inFirst);
        var b = DecisionModelResolver.register(second, inSecond);
        try {
            assertSame(inFirst, DecisionModelResolver.resolve(first).orElseThrow());
            assertSame(inSecond, DecisionModelResolver.resolve(second).orElseThrow());
            assertNotSame(DecisionModelResolver.resolve(first).orElseThrow(),
                    DecisionModelResolver.resolve(second).orElseThrow());
        } finally {
            b.close();
            a.close();
        }
    }

    /** A model its test owns: counts the closes the resolver must never make. */
    private static final class Owned implements DecisionModel, AutoCloseable {
        private final String name;
        private final int priority;
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicInteger asked = new AtomicInteger();
        private volatile boolean available = true;

        Owned(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public DecisionResult decide(DecisionRequest request) {
            asked.incrementAndGet();
            return TestDecisionModels.measured(request, 1.0);
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
