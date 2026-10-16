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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    /** What {@link #onEnvironmentClose()} throws, after releasing its clients, in an environment. */
    private static final Map<AiEnvironment, Throwable> CLOSE_FAILURES = new ConcurrentHashMap<>();
    /** In an environment, {@link #createNativeClient} counts the first down and waits for the second. */
    private static final Map<AiEnvironment, CountDownLatch[]> BLOCKED_CREATES = new ConcurrentHashMap<>();

    /** Every client {@link #createNativeClient} built for this instance. */
    private final List<Client> created = new CopyOnWriteArrayList<>();

    /** A native client that records how often it was closed. */
    public static final class Client {
        private final AtomicInteger closes = new AtomicInteger();

        int closes() {
            return closes.get();
        }
    }

    public static void enable(AiEnvironment environment) {
        ENABLED.add(environment);
    }

    public static void disable(AiEnvironment environment) {
        ENABLED.remove(environment);
        CLOSE_FAILURES.remove(environment);
        BLOCKED_CREATES.remove(environment);
    }

    /**
     * Make {@link #createNativeClient} in {@code environment} count
     * {@code entered} down, then wait (up to ten seconds) for {@code release}.
     */
    static void blockCreate(AiEnvironment environment, CountDownLatch entered, CountDownLatch release) {
        BLOCKED_CREATES.put(environment, new CountDownLatch[] {entered, release});
    }

    /**
     * Make {@link #onEnvironmentClose()} throw {@code failure} in
     * {@code environment}, even a checked exception, as an implementation
     * written in a language without checked exceptions (Kotlin) may.
     */
    public static void failClose(AiEnvironment environment, Throwable failure) {
        CLOSE_FAILURES.put(environment, failure);
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
        var blocked = attachedEnvironment().map(BLOCKED_CREATES::get).orElse(null);
        if (blocked != null) {
            blocked[0].countDown();
            try {
                blocked[1].await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        var client = new Client();
        created.add(client);
        return client;
    }

    /** The clients {@link #createNativeClient} built for this instance. */
    List<Client> createdClients() {
        return List.copyOf(created);
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

    @Override
    public void onEnvironmentClose() {
        super.onEnvironmentClose();
        var failure = attachedEnvironment().map(CLOSE_FAILURES::get).orElse(null);
        if (failure != null) {
            throw CloseProbeAgentRuntime.<RuntimeException>sneakyThrow(failure);
        }
    }

    // Throws a checked exception from a method that declares none, as code
    // compiled against an older signature does at run time; the cast is
    // unchecked by design.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
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
