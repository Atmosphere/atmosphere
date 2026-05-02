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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link ToolLoopPolicy} record + {@link ToolLoopPolicies}
 * sidecar helper. Iteration-cap behavior of {@link OpenAiCompatibleClient}
 * (the runtime that actually owns the tool loop) is covered by the existing
 * {@code testMaxToolRoundsRespected} in {@link OpenAiCompatibleClientTest},
 * which validates the {@link ToolLoopPolicy#DEFAULT} preserves the historical
 * 5-iteration cap unchanged. This file focuses on the policy surface and
 * sidecar wiring.
 */
class ToolLoopPolicyTest {

    @Test
    void defaultPreservesHistoricalCap() {
        // Lock in the public default — any future change to maxIterations or
        // overflow behavior is a behavior change for every existing caller and
        // must be intentional.
        assertEquals(5, ToolLoopPolicy.DEFAULT.maxIterations());
        assertEquals(ToolLoopPolicy.OnMaxIterations.COMPLETE_WITHOUT_TOOLS,
                ToolLoopPolicy.DEFAULT.onMaxIterations());
    }

    @Test
    void rejectsZeroOrNegativeIterations() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new ToolLoopPolicy(0, ToolLoopPolicy.OnMaxIterations.FAIL));
        assertTrue(ex.getMessage().contains("maxIterations"),
                "diagnostic must name the offending field");

        assertThrows(IllegalArgumentException.class,
                () -> new ToolLoopPolicy(-1, ToolLoopPolicy.OnMaxIterations.FAIL));
    }

    @Test
    void rejectsNullOnMaxIterations() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolLoopPolicy(5, null));
    }

    @Test
    void factoryHelpers() {
        var lenient = ToolLoopPolicy.maxIterations(10);
        assertEquals(10, lenient.maxIterations());
        assertEquals(ToolLoopPolicy.OnMaxIterations.COMPLETE_WITHOUT_TOOLS,
                lenient.onMaxIterations(),
                "maxIterations() factory must inherit the lenient overflow default");

        var strict = ToolLoopPolicy.strict(3);
        assertEquals(3, strict.maxIterations());
        assertEquals(ToolLoopPolicy.OnMaxIterations.FAIL, strict.onMaxIterations(),
                "strict() factory must opt into FAIL overflow so callers see the cap hit");
    }

    @Test
    void exhaustedExceptionExposesMaxIterations() {
        var ex = new ToolLoopPolicy.ToolLoopExhaustedException(7);
        assertEquals(7, ex.maxIterations());
        assertTrue(ex.getMessage().contains("7"),
                "message must include the cap so log readers see the bound that was hit");
    }

    @Test
    void sidecarFromReturnsNullWhenAbsent() {
        assertNull(ToolLoopPolicies.from(baseContext(Map.of())),
                "default path: no metadata slot means no policy override");
    }

    @Test
    void sidecarFromReturnsNullForNullContext() {
        assertNull(ToolLoopPolicies.from(null));
    }

    @Test
    void sidecarFromOrDefaultFallsBackToDEFAULT() {
        assertSame(ToolLoopPolicy.DEFAULT,
                ToolLoopPolicies.fromOrDefault(baseContext(Map.of())));
    }

    @Test
    void sidecarRoundTripsPolicy() {
        var policy = ToolLoopPolicy.strict(3);
        var ctx = baseContext(Map.of());
        var attached = ToolLoopPolicies.attach(ctx, policy);

        assertSame(policy, ToolLoopPolicies.from(attached));
        assertNull(ToolLoopPolicies.from(ctx),
                "original context must remain unmutated (immutable metadata)");
    }

    @Test
    void sidecarReplacesPreviousPolicy() {
        var first = ToolLoopPolicy.maxIterations(2);
        var second = ToolLoopPolicy.strict(8);

        var afterFirst = ToolLoopPolicies.attach(baseContext(Map.of()), first);
        var afterSecond = ToolLoopPolicies.attach(afterFirst, second);

        assertSame(second, ToolLoopPolicies.from(afterSecond),
                "second attach must replace the first — exclusive slot");
    }

    @Test
    void sidecarRejectsTypeMismatch() {
        var ctx = baseContext(Map.of(ToolLoopPolicies.METADATA_KEY, "not-a-policy"));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ToolLoopPolicies.from(ctx));
        assertTrue(ex.getMessage().contains(ToolLoopPolicies.METADATA_KEY),
                "diagnostic must name the slot key so misconfiguration is loud");
    }

    @Test
    void sidecarRejectsNullArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolLoopPolicies.attach(null, ToolLoopPolicy.DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> ToolLoopPolicies.attach(baseContext(Map.of()), null));
    }

    @Test
    void chatCompletionRequestDefaultsToDEFAULTPolicy() {
        // Existing call sites that build a ChatCompletionRequest without
        // touching the new field MUST inherit ToolLoopPolicy.DEFAULT — that
        // is what makes this change additive (no behavior change for any
        // pre-policy caller).
        var request = ChatCompletionRequest.builder("gpt-4")
                .user("hi")
                .build();
        assertSame(ToolLoopPolicy.DEFAULT, request.toolLoopPolicy());
    }

    @Test
    void chatCompletionRequestBuilderAcceptsCustomPolicy() {
        var policy = ToolLoopPolicy.strict(2);
        var request = ChatCompletionRequest.builder("gpt-4")
                .user("hi")
                .toolLoopPolicy(policy)
                .build();
        assertSame(policy, request.toolLoopPolicy());

        // Null on the builder restores DEFAULT (matches retryPolicy null-restore convention).
        var nullified = ChatCompletionRequest.builder("gpt-4")
                .user("hi")
                .toolLoopPolicy(policy)
                .toolLoopPolicy(null)
                .build();
        assertSame(ToolLoopPolicy.DEFAULT, nullified.toolLoopPolicy());
    }

    @Test
    void chatCompletionRequestCanonicalConstructorNullDefaults() {
        // Direct positional construction with null toolLoopPolicy must
        // resolve to DEFAULT — guarantees framework runtimes that build a
        // ChatCompletionRequest manually never accidentally NPE the
        // OpenAiCompatibleClient tool-loop guard.
        var request = new ChatCompletionRequest("m", List.of(), 0.0, 100, false,
                List.of(), null, null, List.of(), List.of(), null, null, null, null);
        assertNotNull(request.toolLoopPolicy());
        assertSame(ToolLoopPolicy.DEFAULT, request.toolLoopPolicy());
    }

    @Test
    void shimConstructor13ArgInheritsDEFAULT() {
        // Existing callers using the 13-arg shim (pre-policy canonical
        // signature) MUST inherit ToolLoopPolicy.DEFAULT so binary-compat
        // call sites are bit-identical.
        var request = new ChatCompletionRequest("m", List.of(), 0.0, 100, false,
                List.of(), null, null, List.of(), List.of(), null, null, null);
        assertSame(ToolLoopPolicy.DEFAULT, request.toolLoopPolicy());
    }

    @Test
    void metadataKeyIsStable() {
        // Lock in the wire-format key.
        assertEquals("ai.toolLoop.policy", ToolLoopPolicies.METADATA_KEY);
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        // Need a mutable view since the helper test uses Map.of() (immutable)
        // and then attach() copies; we always start with a fresh wrapper to
        // avoid surprising aliasing between tests.
        Map<String, Object> meta = new HashMap<>(metadata);
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), meta,
                List.of(), null, null);
    }
}
