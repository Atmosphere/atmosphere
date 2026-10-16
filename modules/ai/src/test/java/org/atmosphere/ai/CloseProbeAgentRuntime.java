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
package org.atmosphere.ai;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A runtime registered with {@code ServiceLoader} for the tests only, available
 * only in the environments a test {@link #enable enables} (so every other test
 * of the module sees it as unavailable). Like the Spring AI runtime, it uses a
 * client the application bound when there is one, else builds its own; it
 * counts the closes of each client it is asked to release.
 */
public final class CloseProbeAgentRuntime extends AbstractAgentRuntime<CloseProbeAgentRuntime.Client> {

    private static final Set<AiEnvironment> ENABLED = ConcurrentHashMap.newKeySet();

    /** A native client that records how often it was closed. */
    public static final class Client {
        private final AtomicInteger closes = new AtomicInteger();

        int closes() {
            return closes.get();
        }
    }

    static void enable(AiEnvironment environment) {
        ENABLED.add(environment);
    }

    static void disable(AiEnvironment environment) {
        ENABLED.remove(environment);
    }

    @Override
    public String name() {
        return "close-probe";
    }

    @Override
    public boolean isAvailable() {
        return attachedEnvironment().map(ENABLED::contains).orElse(false);
    }

    @Override
    protected String nativeClientClassName() {
        return Client.class.getName();
    }

    @Override
    protected Client createNativeClient(AiConfig.LlmSettings settings) {
        return new Client();
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        var bound = environment().client(CloseProbeAgentRuntime.class, Client.class, null);
        if (getNativeClient() == null && bound != null) {
            setNativeClient(bound);
        }
        super.configure(settings);
    }

    @Override
    protected void closeNativeClient(Client client) {
        client.closes.incrementAndGet();
    }

    /** The client this instance uses, building it as a dispatch would. */
    Client client() {
        configure(null);
        return getNativeClient();
    }

    @Override
    protected void doExecute(Client client, AgentExecutionContext context, StreamingSession session) {
        session.complete();
    }

    @Override
    protected String clientDescription() {
        return "close probe client";
    }
}
