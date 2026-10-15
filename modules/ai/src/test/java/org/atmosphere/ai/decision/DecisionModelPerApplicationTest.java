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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Two applications in one JVM each get their own fallback decision model, on
 * their own runtime. The resolver used to cache one fallback for the JVM, so
 * the application that resolved first answered every other application's
 * questions with its runtime and client.
 */
class DecisionModelPerApplicationTest {

    private AiEnvironment first;
    private AiEnvironment second;

    @BeforeEach
    void setUp() {
        TestDecisionModels.reset();
        DecisionModelResolver.reset();
        first = AiEnvironment.create(key -> null);
        second = AiEnvironment.create(key -> null);
        first.configure("remote", "model-first", "key-first", "http://127.0.0.1:9/first/v1");
        second.configure("remote", "model-second", "key-second", "http://127.0.0.1:9/second/v1");
    }

    @AfterEach
    void tearDown() {
        second.close();
        first.close();
        DecisionModelResolver.reset();
        TestDecisionModels.reset();
        AgentRuntimeResolver.reset();
    }

    @Test
    void eachApplicationResolvesItsOwnFallback() {
        var inFirst = resolveBoundTo(first);
        var inSecond = resolveBoundTo(second);
        assertInstanceOf(RuntimeDecisionModel.class, inFirst);
        assertInstanceOf(RuntimeDecisionModel.class, inSecond);
        assertNotSame(inFirst, inSecond,
                "the second application must not answer through the first application's runtime");
        assertSame(inFirst, resolveBoundTo(first), "an application keeps its own fallback");
    }

    /** What the static resolver returns on a thread bound to {@code environment}, as a request thread is. */
    private static DecisionModel resolveBoundTo(AiEnvironment environment) {
        var binding = AiEnvironment.bind(environment);
        try {
            return DecisionModelResolver.resolve().orElseThrow();
        } finally {
            binding.close();
        }
    }
}
