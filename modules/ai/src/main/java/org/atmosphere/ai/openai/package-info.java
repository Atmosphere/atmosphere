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
 * Inbound OpenAI-compatible serving endpoint. Exposes registered
 * {@code @Agent} / {@code @AiEndpoint} pipelines as drop-in "models" behind
 * {@code POST /atmosphere/v1/chat/completions} (plus
 * {@code GET /atmosphere/v1/models} for discovery), so tools that speak the
 * OpenAI wire format — Open WebUI, LibreChat, the OpenAI SDKs, LangChain's
 * OpenAI client — can call an Atmosphere agent without any Atmosphere-specific
 * client code.
 *
 * <p>Off by default. Opt in with {@code atmosphere.ai.openai.enabled=true};
 * see {@link org.atmosphere.ai.openai.OpenAiServing} for the full
 * configuration surface. Every request dispatches through
 * {@link org.atmosphere.ai.AiPipeline#execute(String, String,
 * org.atmosphere.ai.StreamingSession)} so admission governance, guardrails,
 * budgets, and cost accounting apply exactly as they do for channel / A2A /
 * AG-UI traffic (Correctness Invariant #7, Mode Parity).</p>
 */
package org.atmosphere.ai.openai;
