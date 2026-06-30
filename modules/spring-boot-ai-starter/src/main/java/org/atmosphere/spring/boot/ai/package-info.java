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

/**
 * AI-inclusive Spring Boot starter for Atmosphere.
 *
 * <p>This module ships <strong>no business code</strong> — it is a dependency
 * aggregator, exactly like a Spring Boot {@code *-starter}. Its sole job is to
 * pull the full Atmosphere AI layer in <em>non-optionally</em> on top of the
 * transport-first {@code atmosphere-spring-boot-starter}:</p>
 *
 * <ul>
 *   <li>{@code atmosphere-spring-boot-starter} — framework runtime, the Spring
 *       Boot auto-configuration, and the Atmosphere Console static assets.</li>
 *   <li>{@code atmosphere-ai} — the AI pipeline, {@code AiConfig}, and the
 *       built-in {@code AgentRuntime} discovery the {@code @Agent} processor
 *       needs at registration time.</li>
 *   <li>{@code atmosphere-agent} — the {@code @Agent} annotation and its
 *       {@code AgentProcessor}.</li>
 *   <li>{@code atmosphere-coordinator} — {@code @Coordinator} / {@code AgentFleet}
 *       multi-agent composition.</li>
 * </ul>
 *
 * <p>The base starter declares those three AI modules
 * {@code <optional>true</optional>} (transport-first by design), so the base
 * starter alone cannot run an {@code @Agent}. Adding <em>this</em> single
 * starter dependency makes the Atmosphere&nbsp;4 promise — "one dependency, a
 * running {@code @Agent} chat app" — genuinely true. The module's own
 * {@code OneDependencyAgentWiringTest} boots a Spring context with only this
 * starter plus a single {@code @Agent} class and asserts the live beans.</p>
 */
package org.atmosphere.spring.boot.ai;
