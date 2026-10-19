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
package org.atmosphere.quarkus.deployment;

import java.io.IOException;
import java.util.List;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.processor.AiEndpointProcessor;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the build-time check that warns when an {@code @AiEndpoint} (or another
 * optional annotation) class is in the Jandex index but its
 * {@code @AtmosphereAnnotation} processor is not: such a class is scanned and
 * then dropped at runtime, so the build must say so instead of staying silent.
 */
class UnhandledOptionalAnnotationsTest {

    @Test
    void anAiEndpointWithoutItsProcessorIsReported() throws IOException {
        var index = Index.of(Endpoint.class);

        assertEquals(List.of(AiEndpoint.class.getName()),
                AtmosphereProcessor.unhandledOptionalAnnotations(index));
    }

    @Test
    void anAiEndpointWithItsProcessorIsNotReported() throws IOException {
        var index = Index.of(Endpoint.class, AiEndpointProcessor.class);

        assertEquals(List.of(), AtmosphereProcessor.unhandledOptionalAnnotations(index));
    }

    @Test
    void anIndexWithoutOptionalAnnotationsReportsNothing() throws IOException {
        var index = Index.of(UnhandledOptionalAnnotationsTest.class);

        assertEquals(List.of(), AtmosphereProcessor.unhandledOptionalAnnotations(index));
    }

    @AiEndpoint(path = "/unhandled-probe")
    static final class Endpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.complete();
        }
    }
}
