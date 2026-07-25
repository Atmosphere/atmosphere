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
package org.atmosphere.ai.prompt;

import org.atmosphere.ai.PromptLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PromptResolverTest {

    private static final String ROLLOUT_KEY = PromptRollout.ROLLOUT_PROPERTY_PREFIX + "greeter";
    private static final String PRODUCT_KEY = PromptTemplate.VAR_PROPERTY_PREFIX + "product";
    private static final String TONE_KEY = PromptTemplate.VAR_PROPERTY_PREFIX + "tone";

    @AfterEach
    public void tearDown() {
        System.clearProperty(ROLLOUT_KEY);
        System.clearProperty(PRODUCT_KEY);
        System.clearProperty(TONE_KEY);
        PromptLoader.clearCache();
    }

    @Test
    public void literalSystemPromptsPassThroughUntouched() {
        var literal = "You are a helpful bot with {{unrendered}} braces.";
        assertSame(literal, PromptResolver.resolveSystemPrompt(literal, "/chat"));
        var skill = "skill:llm-judge";
        assertSame(skill, PromptResolver.resolveSystemPrompt(skill, "/chat"));
        var resource = "prompts/test-system-prompt.md";
        assertSame(resource, PromptResolver.resolveSystemPrompt(resource, "/chat"));
        assertFalse(PromptResolver.isManaged("promptish-literal"));
    }

    @Test
    public void pinnedReferenceResolvesThatExactVersion() {
        assertEquals("Greeter prompt v1.",
                PromptResolver.resolveSystemPrompt("prompt:greeter@v1", "/chat"));
    }

    @Test
    public void latestAndBareWithoutRolloutResolveHighestVersion() {
        assertEquals("Greeter prompt v2.",
                PromptResolver.resolveSystemPrompt("prompt:greeter@latest", "/chat"));
        assertEquals("Greeter prompt v2.",
                PromptResolver.resolveSystemPrompt("prompt:greeter", "/chat"));
    }

    @Test
    public void bareReferenceWithRolloutUsesTheConfiguredSplit() {
        // 100% on v1: proves the rollout path is consulted (latest would be v2)
        // and stays deterministic for the same unit.
        System.setProperty(ROLLOUT_KEY, "v1:1");
        assertEquals("Greeter prompt v1.",
                PromptResolver.resolveSystemPrompt("prompt:greeter", "/chat"));
        // A pinned reference ignores rollout.
        assertEquals("Greeter prompt v2.",
                PromptResolver.resolveSystemPrompt("prompt:greeter@v2", "/chat"));
    }

    @Test
    public void unresolvedTemplateVariableFailsClosedAtResolution() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:templated@v1", "/chat"));
        assertTrue(thrown.getMessage().contains("product"), thrown.getMessage());
    }

    @Test
    public void templateRendersFromConfigDefaultsAndPerRequestVariables() {
        System.setProperty(PRODUCT_KEY, "Atmosphere");
        System.setProperty(TONE_KEY, "calm");
        assertEquals("You support Atmosphere with a calm voice.",
                PromptResolver.resolveSystemPrompt("prompt:templated@v1", "/chat"));
        assertEquals("You support Atmosphere with a cheerful voice.",
                PromptResolver.resolve(PromptReference.parse("prompt:templated@v1"), "/chat",
                        Map.of("tone", "cheerful")));
    }

    @Test
    public void unknownPromptFailsClosedWithAClearError() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:absolutely-unknown", "/chat"));
        assertTrue(thrown.getMessage().contains("absolutely-unknown"), thrown.getMessage());
        var pinned = assertThrows(IllegalStateException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:greeter@v9", "/chat"));
        assertTrue(pinned.getMessage().contains("greeter@v9"), pinned.getMessage());
    }

    @Test
    public void integrityFailureSurfacesThroughTheResolver() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:tampered@v1", "/chat"));
        assertTrue(thrown.getMessage().contains("INTEGRITY FAILURE"), thrown.getMessage());
    }

    @Test
    public void malformedReferencesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:bad/name", "/chat"));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:greeter@2", "/chat"));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResolver.resolveSystemPrompt("prompt:", "/chat"));
    }

    @Test
    public void referenceParsingDistinguishesBareLatestAndPinned() {
        var bare = PromptReference.parse("prompt:greeter");
        assertTrue(bare.bare());
        assertTrue(bare.pinnedVersion().isEmpty());
        var latest = PromptReference.parse("prompt:greeter@latest");
        assertFalse(latest.bare());
        assertTrue(latest.pinnedVersion().isEmpty());
        var pinned = PromptReference.parse("prompt:greeter@v3");
        assertFalse(pinned.bare());
        assertEquals("v3", pinned.pinnedVersion().orElseThrow());
    }
}
