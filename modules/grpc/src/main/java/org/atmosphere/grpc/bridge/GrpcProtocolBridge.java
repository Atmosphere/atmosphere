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
package org.atmosphere.grpc.bridge;

import org.atmosphere.ai.bridge.ProtocolBridge;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link ProtocolBridge} for gRPC-exposed agents. gRPC endpoints are
 * configured at the transport level rather than per Atmosphere handler
 * path, so this bridge receives its {@code isActive} + {@code agentPaths}
 * lambdas from the gRPC server wiring at startup.
 *
 * <p>This keeps the admin enumeration honest: if the gRPC server binding
 * fails, {@link #isActive()} returns {@code false} and the bridge
 * disappears from {@code ProtocolBridgeRegistry.active()} —
 * Correctness Invariant #5 (Runtime Truth).</p>
 */
public final class GrpcProtocolBridge implements ProtocolBridge {

    public static final String NAME = "grpc";

    private final BooleanSupplier activeProbe;
    private final Supplier<List<String>> pathsSupplier;
    private final String descriptor;

    public GrpcProtocolBridge(BooleanSupplier activeProbe,
                              Supplier<List<String>> pathsSupplier,
                              String descriptor) {
        this.activeProbe = Objects.requireNonNull(activeProbe, "activeProbe");
        this.pathsSupplier = Objects.requireNonNull(pathsSupplier, "pathsSupplier");
        this.descriptor = descriptor == null
                ? "gRPC — agent invocations over gRPC bidirectional streams"
                : descriptor;
    }

    /** Convenience constructor for tests or setups that report themselves inactive. */
    public static GrpcProtocolBridge inactive() {
        return new GrpcProtocolBridge(() -> false, List::of,
                "gRPC — not configured");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Kind kind() {
        return Kind.WIRE;
    }

    @Override
    public boolean isActive() {
        return activeProbe.getAsBoolean();
    }

    @Override
    public String describe() {
        return descriptor;
    }

    @Override
    public List<String> agentPaths() {
        return isActive() ? List.copyOf(pathsSupplier.get()) : List.of();
    }

    @Override
    public int order() {
        return 50;
    }
}
