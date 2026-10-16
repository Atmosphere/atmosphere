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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AgentRuntime} written the way a third party may have written one:
 * it is also {@link AutoCloseable}, with its own {@code close() throws
 * Exception}, a release the application calls when it decides to. Registered
 * with {@code ServiceLoader} for the tests only; available only in the
 * environments a test {@link #enable enables}, and recorded only in the ones it
 * {@link #watch watches}.
 */
// "try": javac warns that close() throws Exception could throw an
// InterruptedException; that signature is the third-party one under test.
@SuppressWarnings("try")
public final class ThirdPartyCloseableAgentRuntime implements AgentRuntime, AutoCloseable {

    private static final Set<AiEnvironment> ENABLED = ConcurrentHashMap.newKeySet();
    private static final Map<AiEnvironment, List<ThirdPartyCloseableAgentRuntime>> WATCHED =
            new ConcurrentHashMap<>();

    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger environmentCloses = new AtomicInteger();
    private volatile AiEnvironment environment;

    /** Record every instance attached to {@code environment}, available or not. */
    public static void watch(AiEnvironment environment) {
        WATCHED.putIfAbsent(environment, new CopyOnWriteArrayList<>());
    }

    /** Make the instances attached to {@code environment} available, and watch them. */
    public static void enable(AiEnvironment environment) {
        watch(environment);
        ENABLED.add(environment);
    }

    public static void disable(AiEnvironment environment) {
        ENABLED.remove(environment);
        WATCHED.remove(environment);
    }

    /** The instances attached to a watched {@code environment}, in load order. */
    public static List<ThirdPartyCloseableAgentRuntime> attachedTo(AiEnvironment environment) {
        return List.copyOf(WATCHED.getOrDefault(environment, List.of()));
    }

    @Override
    public void attach(AiEnvironment environment) {
        this.environment = environment;
        var watched = WATCHED.get(environment);
        if (watched != null) {
            watched.add(this);
        }
    }

    @Override
    public Optional<AiEnvironment> attachedEnvironment() {
        return Optional.ofNullable(environment);
    }

    @Override
    public String name() {
        return "third-party-closeable";
    }

    @Override
    public boolean isAvailable() {
        var attached = environment;
        return attached != null && ENABLED.contains(attached);
    }

    @Override
    public int priority() {
        return -1000;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        session.complete();
    }

    /** The third party's own release, for the application to call. */
    @Override
    public void close() throws Exception {
        closes.incrementAndGet();
    }

    @Override
    public void onEnvironmentClose() {
        environmentCloses.incrementAndGet();
    }

    /** @return how often {@link #close()} ran */
    public int closes() {
        return closes.get();
    }

    /** @return how often {@link #onEnvironmentClose()} ran */
    public int environmentCloses() {
        return environmentCloses.get();
    }
}
