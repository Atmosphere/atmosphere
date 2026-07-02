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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;

/**
 * Extension point applied by {@link AiEndpointProcessor} to every
 * {@code @AiEndpoint} handler just before it is registered, so downstream
 * modules can layer behavior onto an AI endpoint without inverting the module
 * dependency (they depend on {@code atmosphere-ai}, not the other way round).
 *
 * <p>The motivating consumer is slash-command routing: {@code atmosphere-agent}
 * already wraps an {@link AiEndpointHandler} with command dispatch for
 * {@code @Agent} classes; a decorator lets the identical mechanism reach
 * {@code @AiEndpoint} classes that declare {@code @Command} methods, so slash
 * commands behave the same across both endpoint styles (Correctness
 * Invariant #7, Mode Parity). Decorators are discovered via {@link
 * java.util.ServiceLoader}; when none are on the classpath, endpoints register
 * exactly as before.</p>
 *
 * <p>Contract: return {@code handler} unchanged when the decorator does not
 * apply. Decorators run in discovery order; each sees the (possibly already
 * decorated) result of the previous one.</p>
 */
public interface AiHandlerDecorator {

    /**
     * Optionally wrap {@code handler} with additional behavior.
     *
     * @param handler the current handler (an {@link AiEndpointHandler} on the
     *                first pass; a prior decorator's result thereafter)
     * @param context the endpoint being registered
     * @return the wrapped handler, or {@code handler} unchanged when this
     *         decorator does not apply
     */
    AtmosphereHandler decorate(AtmosphereHandler handler, Context context);

    /**
     * The {@code @AiEndpoint} under registration.
     *
     * @param target      the endpoint instance
     * @param targetClass the endpoint's class (scan this for annotations)
     * @param framework   the owning framework
     * @param path        the endpoint's registration path
     */
    record Context(Object target, Class<?> targetClass, AtmosphereFramework framework, String path) {
    }
}
