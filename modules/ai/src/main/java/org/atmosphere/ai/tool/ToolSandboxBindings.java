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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * {@link ToolSandboxBinding} discovery. Callers resolve the binding once at
 * registration time (per tool method / per handler) and cache the result
 * themselves — no process-wide cache here, so no classloader pinning and no
 * unbounded map keyed by external input (Invariant #3).
 */
public final class ToolSandboxBindings {

    private static final Logger logger = LoggerFactory.getLogger(ToolSandboxBindings.class);

    private ToolSandboxBindings() {
    }

    /**
     * The first ServiceLoader-discovered binding that
     * {@link ToolSandboxBinding#appliesTo(Method) applies} to {@code method},
     * or empty when none does (zero providers on the classpath included).
     *
     * <p>A {@link ServiceConfigurationError} is logged and treated as "no
     * binding". This does not fail open for sandbox-typed tools: a method
     * that declares a sandbox parameter without a claiming binding is
     * rejected at invocation by {@link DefaultToolRegistry}'s reflective
     * executor rather than run in-JVM.</p>
     */
    public static Optional<ToolSandboxBinding> find(Method method) {
        if (method == null) {
            return Optional.empty();
        }
        try {
            for (var binding : ServiceLoader.load(ToolSandboxBinding.class)) {
                if (binding.appliesTo(method)) {
                    return Optional.of(binding);
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.error("ToolSandboxBinding discovery failed — sandbox routing for {}.{} "
                    + "is unavailable", method.getDeclaringClass().getName(), method.getName(), e);
        }
        return Optional.empty();
    }
}
