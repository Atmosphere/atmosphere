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
package org.atmosphere.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the prompt handler within an {@link AiEndpoint}-annotated class.
 *
 * <p>The method must accept either:</p>
 * <ul>
 *   <li>{@code (String message, StreamingSession session)} — message + streaming session</li>
 *   <li>{@code (String message, StreamingSession session, AtmosphereResource resource)} — with resource access</li>
 * </ul>
 *
 * <p>The method is invoked on a virtual thread so it may perform blocking I/O
 * (e.g., HTTP calls to LLM APIs) without blocking the Atmosphere thread pool.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Prompt {
}
