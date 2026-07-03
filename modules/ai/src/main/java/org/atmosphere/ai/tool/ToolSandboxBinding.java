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
package org.atmosphere.ai.tool;

import java.lang.reflect.Method;

/**
 * Routes a tool-method invocation through an isolated execution scope.
 * Discovered via {@link java.util.ServiceLoader}; {@code atmosphere-sandbox}
 * ships the {@code @SandboxTool} implementation. The SPI lives here — matched
 * by {@link Method}, injected by {@code Class<?>} — so {@code atmosphere-ai}
 * never depends on {@code atmosphere-sandbox} (same dependency-direction rule
 * as the class-name-based injectable whitelist in {@link DefaultToolRegistry}).
 *
 * <p>Contract at the invocation seam ({@link DefaultToolRegistry}'s reflective
 * executor and the {@code @Prompt} dispatch in
 * {@link org.atmosphere.ai.processor.PromptMethodInvoker}): when a binding
 * {@link #appliesTo(Method) applies} to a method, the framework calls
 * {@link #open(Method)} before each invocation, adds the scope's injectable to
 * the parameter-resolution scope, and closes the scope on every terminal path
 * — success, exception, timeout, interrupt (Correctness Invariants #1/#2: the
 * framework created the resource, the framework releases it; the tool method
 * must never close the injected instance).</p>
 *
 * <p>{@link #open(Method)} MUST fail fast with a descriptive exception when
 * the required backend is unavailable — never fall back to in-JVM execution
 * (Correctness Invariant #6, fail closed). The thrown message surfaces as the
 * tool-result error returned to the model; the endpoint keeps running.</p>
 */
public interface ToolSandboxBinding {

    /**
     * Whether this binding manages an isolated scope for {@code method}.
     * Consulted once at tool-registration time; must be cheap and free of
     * side effects (typically an annotation-presence check).
     */
    boolean appliesTo(Method method);

    /**
     * Open a per-invocation scope for {@code method}. Called once per tool
     * invocation, immediately before the method dispatch. The isolated
     * resource is created here — not at registration — so an unavailable
     * backend fails the individual call, not application startup.
     *
     * @throws IllegalStateException when the backend the method requires is
     *                               not available; the message must name the
     *                               backend and how to enable it
     */
    SandboxScope open(Method method);

    /**
     * A live per-invocation scope: the injectable instance the tool method
     * receives, plus the release hook the framework runs when the invocation
     * completes.
     */
    interface SandboxScope extends AutoCloseable {

        /** The declared parameter type the injectable is keyed under. */
        Class<?> injectableType();

        /** The instance to inject; owned by the framework, not the tool. */
        Object injectable();

        /** Release the scope's resources. MUST be idempotent (Invariant #2). */
        @Override
        void close();
    }
}
