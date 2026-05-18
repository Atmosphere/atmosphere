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
 * JDK Flight Recorder event types for AI agent observability.
 *
 * <p>Every agent turn, tool invocation, model call, session lifecycle
 * transition, and error path emits a JFR event under category
 * {@code [Atmosphere, AI]}. Events are zero-cost when no JFR recording is
 * active (the event's {@code shouldCommit()} short-circuits).</p>
 *
 * <p>Open recordings in JDK Mission Control and filter by category
 * {@code Atmosphere / AI} to see agent turns, model calls, and tool
 * invocations correlated by event start time.</p>
 *
 * <p>Wiring: {@link org.atmosphere.ai.jfr.JfrAiMetrics} is composed with any
 * user-supplied {@link org.atmosphere.ai.AiMetrics} via
 * {@link org.atmosphere.ai.jfr.CompositeAiMetrics#withJfr}. The pipeline emits
 * {@link org.atmosphere.ai.jfr.AgentTurnEvent} directly around each
 * {@code runtime.execute()} call.</p>
 */
package org.atmosphere.ai.jfr;
