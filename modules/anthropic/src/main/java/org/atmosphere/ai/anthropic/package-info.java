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
 * Native Anthropic Messages API ({@code POST /v1/messages}) runtime for the
 * Atmosphere {@link org.atmosphere.ai.AgentRuntime} SPI. No SDK dependency
 * — talks directly to {@code https://api.anthropic.com/v1/messages} via
 * {@link java.net.http.HttpClient}.
 *
 * <p>Use this runtime instead of routing Claude through the built-in
 * OpenAI-compatible client when:</p>
 * <ul>
 *   <li>You want Anthropic-native content blocks
 *       ({@code text} / {@code tool_use} / {@code tool_result}) instead of
 *       the OpenAI-shaped translation an OAI-compat proxy performs.</li>
 *   <li>You need features that only live on the Messages API
 *       (Anthropic-specific stop reasons, native prompt-cache control
 *       blocks, thinking/redacted-thinking content).</li>
 *   <li>You want to avoid the OpenAI-compatible proxy hop entirely so
 *       your wire format matches the published Anthropic spec.</li>
 * </ul>
 *
 * @see org.atmosphere.ai.anthropic.AnthropicAgentRuntime
 * @see org.atmosphere.ai.anthropic.AnthropicMessagesClient
 */
package org.atmosphere.ai.anthropic;
