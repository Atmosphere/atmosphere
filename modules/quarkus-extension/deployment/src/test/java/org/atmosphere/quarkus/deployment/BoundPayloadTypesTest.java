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

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the payload-type collection behind the native-image reflection
 * registration. The regression this guards: codec classes were registered but
 * the payload type they bind was not, so under Native Image the handler
 * registered, the decoder loaded, and the first real {@code @Message} dispatch
 * failed silently — caught live by the native CI probe, invisible to every JVM
 * test.
 */
class BoundPayloadTypesTest {

    @Test
    void collectsMessageSignatureTypes() throws IOException {
        var index = Index.of(FixtureEndpoint.class, FixturePayload.class);

        var types = AtmosphereProcessor.boundPayloadTypes(index);

        assertTrue(types.contains(FixturePayload.class.getName()),
                "the @Message parameter/return type must be registered for reflection");
    }

    @Test
    void skipsJdkTypesButNotFrameworkOnes() throws IOException {
        var index = Index.of(FixtureEndpoint.class, FixturePayload.class);

        var types = AtmosphereProcessor.boundPayloadTypes(index);

        assertFalse(types.stream().anyMatch(name -> name.startsWith("java.")),
                "JDK types need no registration");
        assertTrue(types.contains(AtmosphereResource.class.getName()),
                "no package filter beyond the JDK: a broader one silently excluded "
                        + "org.atmosphere.samples.* payloads, the exact regression class");
    }

    @ManagedService(path = "/payload-fixture")
    static final class FixtureEndpoint {

        @Ready
        public void onReady(AtmosphereResource resource) {
        }

        @org.atmosphere.config.service.Message
        public FixturePayload onMessage(FixturePayload payload) {
            return payload;
        }
    }

    static final class FixturePayload {
    }
}
