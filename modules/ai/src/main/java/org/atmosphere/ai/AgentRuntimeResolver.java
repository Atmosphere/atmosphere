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

import org.atmosphere.ai.llm.BuiltInAgentRuntime;

import java.util.List;

/**
 * Resolves {@link AgentRuntime} implementations from the classpath via
 * {@link ServiceLoader}. Falls back to {@link BuiltInAgentRuntime} if no
 * higher-priority runtime is available.
 *
 * <p>When {@link AiConfig#RUNTIME_PROPERTY} ({@code atmosphere.ai.runtime},
 * env {@code LLM_RUNTIME}, or a host configuration) names a runtime, the
 * available runtime with that {@link AgentRuntime#name()} is the only one
 * resolved, whatever its priority. A name that matches no available runtime
 * fails resolution with an {@link AgentRuntimeSelectionException} (an
 * {@link IllegalStateException}) naming the requested and the available
 * runtimes, rather than running a runtime that was not configured.</p>
 *
 * <p>The statics resolve the runtimes of {@link AiEnvironment#current()}: the
 * application bound to the calling thread, else the only one running, else
 * the process default. Framework code that holds an application uses
 * {@link AiEnvironment#runtimes()} instead.</p>
 *
 * @see AgentRuntime
 * @see AgentRuntimeRegistry
 */
public final class AgentRuntimeResolver {

    // True once application code has explicitly bound a native client to a
    // runtime adapter (SpringAiAgentRuntime.setChatClient,
    // LangChain4jAgentRuntime.setModel, ...). The demo fallback keys its
    // availability off "no API key configured"; a bound client means a real
    // runtime can serve without any key, so demo must yield. The static
    // binders apply to every application, so this flag does too; a binding
    // made for one application is marked on its AiEnvironment instead.
    private static volatile boolean explicitClientBinding;

    private AgentRuntimeResolver() {}

    /**
     * Records that application code explicitly bound a native client to a
     * runtime adapter, and drops the cached resolution so the next
     * {@link #resolveAll()} re-evaluates availability. Called by the runtime
     * adapters' static binder methods — never by auto-configuration paths,
     * which must offer rather than bind.
     */
    public static void markExplicitClientBinding() {
        explicitClientBinding = true;
        reset();
    }

    /**
     * Whether application code has explicitly bound a native client to any
     * runtime adapter in this JVM.
     *
     * @return {@code true} once {@link #markExplicitClientBinding()} ran
     */
    public static boolean hasExplicitClientBinding() {
        return explicitClientBinding;
    }

    /**
     * Test hook: clears the {@link #markExplicitClientBinding()} mark, the
     * native clients bound or offered to the process default and to every
     * open {@link AiEnvironment} (and their marks), and the cached resolution,
     * restoring the pristine no-binding state.
     */
    public static void clearExplicitClientBinding() {
        explicitClientBinding = false;
        AiEnvironment.clearAllBindings();
        reset();
    }

    /**
     * Returns the runtime {@link AiConfig#RUNTIME_PROPERTY} names, else the
     * highest-priority available runtime, or {@link BuiltInAgentRuntime} as
     * fallback.
     *
     * @throws AgentRuntimeSelectionException when {@link AiConfig#RUNTIME_PROPERTY}
     *                                         names no available runtime
     */
    public static AgentRuntime resolve() {
        return resolveAll().getFirst();
    }

    /**
     * Drop the cached runtime list of the process default and of every open
     * {@link AiEnvironment}, so the next {@link #resolveAll()} call rescans the
     * ServiceLoader. Visible for tests that install a custom runtime before
     * the first resolution — without this, the cache freezes at the first call
     * and subsequent {@code configure(...)} changes stay invisible.
     */
    public static void reset() {
        AiEnvironment.resetRuntimeCaches();
    }

    /**
     * Returns all available runtimes sorted by priority (highest first).
     * Falls back to a singleton list with {@link BuiltInAgentRuntime} if
     * no implementations are discovered. When {@link AiConfig#RUNTIME_PROPERTY}
     * is set, returns only the runtime it names.
     *
     * <p>Results are cached after first resolution — the classpath does not
     * change at runtime so the discovered runtimes are stable.</p>
     *
     * @throws AgentRuntimeSelectionException when {@link AiConfig#RUNTIME_PROPERTY}
     *                                         names no available runtime
     */
    public static List<AgentRuntime> resolveAll() {
        return AiEnvironment.current().runtimes().resolveAll();
    }
}
