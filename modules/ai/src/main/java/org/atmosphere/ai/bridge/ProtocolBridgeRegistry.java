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
package org.atmosphere.ai.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Eager discovery of {@link ProtocolBridge} implementations via
 * {@link ServiceLoader}. Constructed once at application startup; callers
 * enumerate bridges for admin inspection and for "which protocols reach
 * this agent" queries.
 *
 * <p>The registry is read-mostly — adding a bridge at runtime is possible
 * via {@link #register(ProtocolBridge)} but the typical pattern is
 * ServiceLoader discovery plus an optional explicit register call from the
 * host framework (Spring Boot starter, Quarkus extension).</p>
 */
public final class ProtocolBridgeRegistry {

    private final List<ProtocolBridge> bridges;

    public ProtocolBridgeRegistry() {
        this(discover());
    }

    public ProtocolBridgeRegistry(List<ProtocolBridge> bridges) {
        var sorted = new ArrayList<>(bridges);
        sorted.sort(Comparator.comparingInt(ProtocolBridge::order)
                .thenComparing(ProtocolBridge::name));
        this.bridges = new ArrayList<>(sorted);
    }

    /** Return all bridges in order, regardless of {@link ProtocolBridge#isActive()}. */
    public List<ProtocolBridge> all() {
        return List.copyOf(bridges);
    }

    /** Return only the bridges currently reporting as active. */
    public List<ProtocolBridge> active() {
        return bridges.stream().filter(ProtocolBridge::isActive).toList();
    }

    /** Find a specific bridge by its stable {@link ProtocolBridge#name()}. */
    public Optional<ProtocolBridge> byName(String name) {
        for (var bridge : bridges) {
            if (bridge.name().equals(name)) {
                return Optional.of(bridge);
            }
        }
        return Optional.empty();
    }

    /** Register an additional bridge at runtime (typically from host-framework wiring). */
    public synchronized void register(ProtocolBridge bridge) {
        bridges.add(bridge);
        bridges.sort(Comparator.comparingInt(ProtocolBridge::order)
                .thenComparing(ProtocolBridge::name));
    }

    private static List<ProtocolBridge> discover() {
        var list = new ArrayList<ProtocolBridge>();
        for (var bridge : ServiceLoader.load(ProtocolBridge.class)) {
            list.add(bridge);
        }
        return list;
    }
}
