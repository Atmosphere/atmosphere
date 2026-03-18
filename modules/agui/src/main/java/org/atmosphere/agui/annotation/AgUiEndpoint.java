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
 * Marks a class as an AG-UI (Agent-User Interaction) endpoint. The annotated
 * class must contain exactly one method annotated with {@link AgUiAction}.
 *
 * <p>The endpoint is registered at the specified {@link #path()} and handles
 * AG-UI protocol messages via SSE (Server-Sent Events).</p>
 *
 * @see AgUiAction
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgUiEndpoint {

    /**
     * The URL path at which this AG-UI endpoint is registered.
     */
    String path();
}
