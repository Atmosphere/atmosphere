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
package org.atmosphere.quarkus.grpc.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the Atmosphere gRPC Quarkus extension. All keys live under
 * the {@code quarkus.atmosphere.grpc.*} prefix.
 *
 * <p>The configuration is {@link ConfigPhase#RUN_TIME}: every property is read
 * only when the gRPC server is started in the {@code StartupEvent} observer,
 * so changing them does not require a re-build.
 *
 * <p>Mirrors the Spring Boot starter's {@code atmosphere.grpc.*} block.</p>
 */
@ConfigMapping(prefix = "quarkus.atmosphere.grpc")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AtmosphereQuarkusGrpcConfig {

    /**
     * Whether the Atmosphere gRPC server is started. Defaults to {@code false}
     * so adding the extension to the classpath does not silently open a port.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Standalone Netty gRPC port. {@code 0} requests an ephemeral port, which
     * the test harness uses to avoid clashes.
     */
    @WithDefault("9090")
    int port();

    /**
     * Whether the standard {@code grpc.reflection.v1alpha.ServerReflection}
     * service is exposed. Enabled by default so {@code grpcurl} can list
     * services without a {@code .proto} file.
     */
    @WithDefault("true")
    boolean enableReflection();
}
