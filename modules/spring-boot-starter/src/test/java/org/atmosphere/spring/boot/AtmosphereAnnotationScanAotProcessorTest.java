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

import org.atmosphere.config.service.Message;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which types an annotated handler drags into a native image with it.
 *
 * <p>Registering {@code @Message}\u0027s encoder and decoder is not enough. Those
 * classes are named by class literal and are picked up from the annotation, but
 * the DTO they convert appears only as the annotated method\u0027s parameter and
 * return type — and Jackson reflects over that DTO\u0027s constructor and fields.
 * Without it, a native image gets as far as invoking the codec and then fails on
 * the payload, which reads as a broken transport rather than a missing hint.</p>
 *
 * <p>The exclusion filter is pinned too, because the obvious version of it was
 * wrong: skipping {@code org.atmosphere.*} as "framework types" also skips
 * {@code org.atmosphere.samples.*}, which is where every sample DTO lives. That
 * filter silently defeated the registration it was part of.</p>
 */
class AtmosphereAnnotationScanAotProcessorTest {

    /** Stand-in for a sample DTO: a plain POJO a codec would have to construct. */
    public static class Payload {
        private String text;

        public Payload() {
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    /** Stand-in for an annotated handler carrying a payload in and out. */
    public static class Handler {
        @Message
        public Payload onMessage(Payload incoming) {
            return incoming;
        }

        public Payload notAnnotated(Payload ignored) {
            return null;
        }
    }

    private static Set<Class<?>> collaboratorsOf(Class<?> handler) throws Exception {
        var processor = new AtmosphereAnnotationScanAotProcessor();
        var m = AtmosphereAnnotationScanAotProcessor.class
                .getDeclaredMethod("collaboratorTypes", Set.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var result = (Set<Class<?>>) m.invoke(processor, Set.of(handler));
        return result;
    }

    @Test
    void thePayloadTypeOfAnAnnotatedMethodIsRegistered() throws Exception {
        assertTrue(collaboratorsOf(Handler.class).contains(Payload.class),
                "the DTO an @Message method receives and returns must be registered — "
                        + "Jackson constructs and populates it reflectively, so a native "
                        + "image without this hint invokes the codec and then fails on the "
                        + "payload");
    }

    @Test
    void aSamplesOwnTypeIsNotMistakenForAFrameworkType() throws Exception {
        // Payload lives under org.atmosphere.spring.boot — inside the
        // org.atmosphere namespace. An exclusion filter keyed on that prefix
        // drops it, which is exactly the bug this pins.
        assertTrue(Payload.class.getName().startsWith("org.atmosphere."),
                "this fixture only tests what it claims to if it sits under the "
                        + "org.atmosphere namespace, as the samples do");
        assertTrue(collaboratorsOf(Handler.class).contains(Payload.class),
                "a type under org.atmosphere.* that belongs to the application must "
                        + "still be registered");
    }

    @Test
    void jdkTypesAreNotRegistered() throws Exception {
        var found = collaboratorsOf(Handler.class);
        assertFalse(found.stream().anyMatch(c -> c.getName().startsWith("java.")),
                "JDK types are supplied by the image; registering them is noise: " + found);
    }
}
