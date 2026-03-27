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
 * Marks a class as an Atmosphere Coordinator — an agent that manages a
 * {@link Fleet} of other agents. Subsumes {@code @Agent}: the
 * {@link CoordinatorProcessor} handles base agent setup internally,
 * then adds fleet wiring.
 *
 * <pre>{@code
 * @Coordinator(name = "ceo", skillFile = "prompts/ceo-skill.md")
 * @Fleet({
 *     @AgentRef(type = ResearchAgent.class),
 *     @AgentRef(value = "finance", version = "2.0.0")
 * })
 * public class CeoCoordinator {
 *
 *     @Prompt
 *     public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
 *         var research = fleet.agent("research").call("web_search", Map.of("query", message));
 *         session.stream("Synthesize: " + research.text());
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Coordinator {

    /** Coordinator name. Used in the registration path and protocol metadata. */
    String name();

    /** Classpath resource path to the skill file (.md). The entire file becomes the system prompt. */
    String skillFile() default "";

    /** Human-readable description. Used in Agent Card metadata. */
    String description() default "";

    /** Coordinator version for Agent Card metadata. */
    String version() default "1.0.0";
}
