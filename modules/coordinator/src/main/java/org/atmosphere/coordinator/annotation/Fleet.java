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
package org.atmosphere.coordinator.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the set of agents that a {@link Coordinator} manages. Applied at
 * class level alongside {@code @Coordinator}. Acts as both documentation and
 * a startup validation contract.
 *
 * <pre>{@code
 * @Coordinator(name = "ceo")
 * @Fleet({
 *     @AgentRef(type = ResearchAgent.class),
 *     @AgentRef(value = "finance", version = "2.0.0"),
 *     @AgentRef(value = "analytics", required = false)
 * })
 * public class CeoCoordinator { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fleet {

    /** The agents this coordinator manages. */
    AgentRef[] value();
}
