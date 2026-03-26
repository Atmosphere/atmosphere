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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.coordinator.transport.AgentTransport;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Default {@link AgentProxy} implementation that delegates to an {@link AgentTransport}.
 */
public final class DefaultAgentProxy implements AgentProxy {

    private final String name;
    private final String version;
    private final int weight;
    private final boolean local;
    private final AgentTransport transport;

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, AgentTransport transport) {
        this.name = name;
        this.version = version;
        this.weight = weight;
        this.local = local;
        this.transport = transport;
    }

    @Override
    public String name() { return name; }

    @Override
    public String version() { return version; }

    @Override
    public boolean isAvailable() { return transport.isAvailable(); }

    @Override
    public int weight() { return weight; }

    @Override
    public boolean isLocal() { return local; }

    @Override
    public AgentResult call(String skill, Map<String, String> args) {
        return transport.send(name, skill, args);
    }

    @Override
    public CompletableFuture<AgentResult> callAsync(String skill, Map<String, String> args) {
        return CompletableFuture.supplyAsync(
                () -> transport.send(name, skill, args),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public void stream(String skill, Map<String, String> args,
                       Consumer<String> onToken, Runnable onComplete) {
        transport.stream(name, skill, args, onToken, onComplete);
    }
}
