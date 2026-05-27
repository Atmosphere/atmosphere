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
package org.atmosphere.agui.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an AG-UI action handler bound through {@link
 * org.atmosphere.agui.runtime.AgUiHandler}. The method receives a {@link
 * org.atmosphere.agui.runtime.RunContext} and an {@link
 * org.atmosphere.agui.runtime.AgUiStreamingSession} to produce AG-UI events.
 *
 * <p>The everyday wiring path is automatic: an {@code @Agent} class with a
 * {@code @Prompt} method is auto-bridged into AG-UI without needing this
 * annotation. {@code @AgUiAction} is the explicit hook used when a handler
 * needs direct access to the {@code RunContext} instead of going through the
 * default {@code @Prompt} bridge.</p>
 *
 * <p>Expected signature:</p>
 * <pre>{@code
 * @AgUiAction
 * public void handleRun(RunContext context, AgUiStreamingSession session) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgUiAction {
}
