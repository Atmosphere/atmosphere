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

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;

import org.atmosphere.quarkus.grpc.runtime.AtmosphereQuarkusGrpcLifecycle;

/**
 * Build-time processor for the {@code atmosphere-quarkus-grpc} extension.
 *
 * <p>Wires the standalone Netty-backed {@link org.atmosphere.grpc.AtmosphereGrpcServer}
 * onto Quarkus lifecycle events by registering the
 * {@link AtmosphereQuarkusGrpcLifecycle} CDI bean. The bean's
 * {@code @Observes StartupEvent} / {@code ShutdownEvent} pair owns
 * {@code Server.start()} / {@code Server.shutdown()}.
 *
 * <p>The proto contract is identical to the Spring Boot starter's
 * {@link org.atmosphere.grpc.AtmosphereGrpcServer} wiring — a {@code wasync}
 * gRPC client connecting to either backend speaks the same
 * {@code org.atmosphere.grpc.AtmosphereService} on the wire.
 */
class AtmosphereQuarkusGrpcProcessor {

    private static final String FEATURE = "atmosphere-grpc";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Registers the lifecycle bean as an unremovable CDI bean. Without
     * {@code setUnremovable}, Quarkus's bean-removal pass may decide nothing
     * directly injects {@code AtmosphereQuarkusGrpcLifecycle} and drop it —
     * but that bean's only purpose is to observe {@code StartupEvent}, so
     * we keep it pinned.
     */
    @BuildStep
    AdditionalBeanBuildItem registerLifecycleBean() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(AtmosphereQuarkusGrpcLifecycle.class)
                .setUnremovable()
                .build();
    }

    /**
     * Native image support. Netty's TLS-related providers are loaded reflectively
     * by grpc-netty-shaded; we declare the {@code AtmosphereGrpcServer} and
     * generated proto messages reachable so {@code mvn package -Pnative}
     * succeeds. These are conservative entries — additional reflective
     * classes can be added by the user via {@code quarkus.native.additional-build-args}
     * if their custom interceptor needs more.
     */
    @BuildStep
    void registerReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                        "org.atmosphere.grpc.AtmosphereGrpcServer",
                        "org.atmosphere.grpc.AtmosphereGrpcService",
                        "org.atmosphere.grpc.GrpcProcessor",
                        "org.atmosphere.grpc.proto.AtmosphereMessage",
                        "org.atmosphere.grpc.proto.AtmosphereServiceGrpc",
                        "org.atmosphere.grpc.proto.AtmosphereServiceGrpc$AtmosphereServiceImplBase",
                        "org.atmosphere.grpc.proto.MessageType")
                .methods()
                .fields()
                .build());
    }

    /**
     * gRPC's Netty bootstrap touches static {@code epoll} / {@code kqueue}
     * descriptors. Marking the AtmosphereGrpcServer class as runtime-init
     * ensures those native handles are created in the running image rather
     * than baked into the native-image heap.
     */
    @BuildStep
    RuntimeInitializedClassBuildItem deferGrpcInit() {
        return new RuntimeInitializedClassBuildItem("org.atmosphere.grpc.AtmosphereGrpcServer");
    }
}
