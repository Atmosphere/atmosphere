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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolSandboxBinding;
import org.atmosphere.ai.tool.ToolSandboxBindings;
import org.atmosphere.cpr.AtmosphereResource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one {@code @Prompt}-method dispatch seam, shared by
 * {@link AiEndpointHandler} (web path) and the A2A / AG-UI bridges in
 * {@code atmosphere-agent} so parameter binding — and the per-invocation
 * {@link ToolSandboxBinding} sandbox scope — behaves identically across
 * every invocation mode (Correctness Invariant #7, Mode Parity).
 *
 * <p>Binding rules, in order per parameter: {@code String} receives the user
 * message; {@link StreamingSession}-assignable receives the live session;
 * {@link AtmosphereResource}-assignable receives the resource (may be
 * {@code null} on resource-free paths); anything else resolves from the
 * injectables map (exact key first, then assignable scan) or fails with a
 * descriptive {@link IllegalStateException}.</p>
 *
 * <p>When a {@link ToolSandboxBinding} claims the method (e.g.
 * {@code @SandboxTool}), each {@link #invoke} opens a fresh scope before
 * dispatch, adds its injectable to the resolution scope, and closes it on
 * every terminal path — success, exception, interrupt (Invariants #1/#2:
 * framework-created, framework-closed; the method must not close the injected
 * instance). A scope-open failure (backend unavailable) propagates to the
 * caller's existing error handling; there is no in-JVM fallback.</p>
 */
public final class PromptMethodInvoker {

    private final Object target;
    private final Method method;
    private final ToolSandboxBinding sandboxBinding;

    private PromptMethodInvoker(Object target, Method method, ToolSandboxBinding sandboxBinding) {
        this.target = target;
        this.method = method;
        this.sandboxBinding = sandboxBinding;
    }

    /**
     * Build an invoker for {@code method}, resolving its (optional)
     * {@link ToolSandboxBinding} once. Call at registration time and reuse
     * per invocation.
     */
    public static PromptMethodInvoker forMethod(Object target, Method method) {
        method.setAccessible(true);
        return new PromptMethodInvoker(target, method,
                ToolSandboxBindings.find(method).orElse(null));
    }

    /**
     * Invoke the prompt method with framework parameter binding.
     *
     * @param message     the user message bound to {@code String} parameters
     * @param session     the live session bound to {@link StreamingSession} parameters
     * @param resource    the originating resource; {@code null} on resource-free
     *                    paths (A2A, AG-UI)
     * @param injectables extra type-keyed instances; {@code null} is treated
     *                    as empty
     */
    public void invoke(String message, StreamingSession session,
                       AtmosphereResource resource,
                       Map<Class<?>, Object> injectables)
            throws InvocationTargetException, IllegalAccessException {
        var scope = injectables != null ? injectables : Map.<Class<?>, Object>of();
        if (sandboxBinding == null) {
            method.invoke(target, bindArguments(message, session, resource, scope));
            return;
        }
        try (var sandboxScope = sandboxBinding.open(method)) {
            var extended = new LinkedHashMap<Class<?>, Object>(scope);
            extended.put(sandboxScope.injectableType(), sandboxScope.injectable());
            method.invoke(target, bindArguments(message, session, resource, extended));
        }
    }

    private Object[] bindArguments(String message, StreamingSession session,
                                   AtmosphereResource resource,
                                   Map<Class<?>, Object> injectables) {
        var paramTypes = method.getParameterTypes();
        var args = new Object[paramTypes.length];
        for (var i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == String.class) {
                args[i] = message;
            } else if (StreamingSession.class.isAssignableFrom(paramTypes[i])) {
                args[i] = session;
            } else if (AtmosphereResource.class.isAssignableFrom(paramTypes[i])) {
                args[i] = resource;
            } else {
                // Exact-key match first (O(1)); assignable-from scan as a
                // fallback so a @Prompt method can declare an SPI interface
                // (AgentState, AgentIdentity, AgentWorkspace) and receive the
                // concrete impl the endpoint processor registered.
                var injectable = injectables.get(paramTypes[i]);
                if (injectable == null) {
                    for (var entry : injectables.entrySet()) {
                        if (paramTypes[i].isAssignableFrom(entry.getKey())) {
                            injectable = entry.getValue();
                            break;
                        }
                    }
                }
                if (injectable != null) {
                    args[i] = injectable;
                } else {
                    throw new IllegalStateException(
                            "Unsupported parameter type in @Prompt method: " + paramTypes[i].getName());
                }
            }
        }
        return args;
    }
}
