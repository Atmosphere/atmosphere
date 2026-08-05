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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.annotation.Prompt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the default AI endpoint's {@code @Prompt} method is resolved.
 *
 * <p>The registrar used to take {@code getDeclaredMethods()[0]}. The order of
 * that array is explicitly unspecified, so the correct method was being picked
 * by luck on the JVM; under GraalVM the array came back empty and the registrar
 * threw {@link ArrayIndexOutOfBoundsException} before the endpoint was created.
 * The prompt method is now bound by name and signature.</p>
 */
class DefaultAiChatEndpointPromptMethodTest {

    @Test
    void thePromptMethodResolvesByNameAndSignature() {
        var method = DefaultAiChatEndpoint.PROMPT_METHOD;

        assertNotNull(method, "the default endpoint cannot be registered without its @Prompt method");
        assertEquals("onPrompt", method.getName());
        assertEquals(2, method.getParameterCount(),
                "the handler is built against (String, StreamingSession)");
    }

    @Test
    void theResolvedMethodIsTheAnnotatedOne() {
        assertTrue(DefaultAiChatEndpoint.PROMPT_METHOD.isAnnotationPresent(Prompt.class),
                "resolving to a method without @Prompt would register an endpoint that "
                        + "never receives a prompt — the failure mode an ordinal lookup "
                        + "invites the moment a second method is added to the class");
    }

    @Test
    void resolutionDoesNotDependOnDeclarationOrder() {
        // The bug this replaces was an ordinal read of getDeclaredMethods(). If the
        // class ever grows a second method, an ordinal lookup could silently pick it;
        // a name+signature lookup cannot. Assert the resolved method is the annotated
        // one among *all* declared methods, however many there are.
        var annotated = java.util.Arrays.stream(DefaultAiChatEndpoint.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Prompt.class))
                .toList();

        assertEquals(1, annotated.size(),
                "exactly one @Prompt method is expected on the default endpoint");
        assertEquals(annotated.get(0), DefaultAiChatEndpoint.PROMPT_METHOD,
                "the bound method must be the annotated one regardless of declaration order");
    }
}
