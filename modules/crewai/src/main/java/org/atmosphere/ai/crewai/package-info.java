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
 * CrewAI (Python) sidecar bridge for the Atmosphere
 * {@link org.atmosphere.ai.AgentRuntime} SPI.
 *
 * <h2>Architecture</h2>
 * <p>CrewAI is a Python multi-agent orchestration framework — it cannot be
 * embedded in a JVM. This module ships only the Java half of the bridge:
 * an {@link org.atmosphere.ai.AgentRuntime} that talks to an out-of-process
 * sidecar over HTTP+SSE. The Python sidecar (which embeds CrewAI itself)
 * ships as a separate package; until it is reachable at the configured
 * URL the runtime advertises {@code isAvailable() == false} per
 * Correctness Invariant #5 (Runtime Truth).</p>
 *
 * <h2>Wire shape</h2>
 * <pre>{@code
 * POST /v1/sessions
 *   Content-Type: application/json
 *   Accept: text/event-stream
 *
 *   { "message": "...",
 *     "model": "...",
 *     "history": [ { "role": "user", "content": "..." } ],
 *     "options": { ... } }
 *
 * Response: 200 OK, text/event-stream
 *   X-Atmosphere-CrewAI-Session: <session-id>
 *
 *   event: token
 *   data: {"text":"Hello"}
 *
 *   event: token
 *   data: {"text":" world"}
 *
 *   event: usage
 *   data: {"input":12,"output":3,"total":15,"model":"gpt-4o-mini"}
 *
 *   event: done
 *   data: {}
 * }</pre>
 *
 * <p>Error frames carry a JSON object with a {@code message} field and
 * terminate the stream:</p>
 * <pre>{@code
 *   event: error
 *   data: {"message":"crew exhausted retries"}
 * }</pre>
 *
 * <p>Cancellation flows through {@code DELETE /v1/sessions/<id>} —
 * idempotent on both ends.</p>
 *
 * <h2>Configuration</h2>
 * <p>Sidecar discovery is via either of:</p>
 * <ul>
 *   <li>{@code ATMOSPHERE_CREWAI_SIDECAR_URL} environment variable</li>
 *   <li>{@code atmosphere.crewai.sidecar.url} system property</li>
 * </ul>
 *
 * <p>See {@link org.atmosphere.ai.crewai.CrewAiSidecarConfig} for the full
 * settings surface.</p>
 *
 * @see org.atmosphere.ai.crewai.CrewAiAgentRuntime
 * @see org.atmosphere.ai.crewai.CrewAiSidecarClient
 * @see org.atmosphere.ai.crewai.HttpSseSidecarClient
 */
package org.atmosphere.ai.crewai;
