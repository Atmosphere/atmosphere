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
package org.atmosphere.quarkus.grpc.deployment;

import io.quarkus.test.QuarkusExtensionTest;

import jakarta.enterprise.inject.spi.CDI;

import org.atmosphere.quarkus.grpc.runtime.AtmosphereQuarkusGrpcLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the gRPC server is <em>not</em> started when
 * {@code quarkus.atmosphere.grpc.enabled=false} (the default). Guards against
 * the most obvious deployment-mode footgun: dropping the extension on the
 * classpath should not open a network port unless the user opts in.
 */
class AtmosphereQuarkusGrpcDisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereQuarkusGrpcDisabledTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.grpc.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");
    // Note: quarkus.atmosphere.grpc.enabled intentionally NOT set — the default
    // is false. If the default ever flips to true, this test fails loudly.

    @Test
    void grpcServerIsNotStartedByDefault() {
        AtmosphereQuarkusGrpcLifecycle lifecycle =
                CDI.current().select(AtmosphereQuarkusGrpcLifecycle.class).get();
        assertEquals(-1, lifecycle.port(),
                "Atmosphere gRPC server must NOT be running when "
                        + "quarkus.atmosphere.grpc.enabled is unset");
    }
}
