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
 * Native Cohere v2 Chat API ({@code POST /v2/chat}) runtime for the
 * Atmosphere {@link org.atmosphere.ai.AgentRuntime} SPI. No SDK
 * dependency — talks directly to {@code https://api.cohere.com/v2/chat}
 * via {@link java.net.http.HttpClient}.
 *
 * <p>Use this runtime instead of routing Cohere through the built-in
 * OpenAI-compatible client or the LangChain4j Cohere adapter when:</p>
 * <ul>
 *   <li>You want Cohere-native SSE event types
 *       ({@code content-delta}, {@code tool-plan-delta},
 *       {@code tool-call-start}, {@code tool-call-delta},
 *       {@code citation-start}) so streaming "thinking" and citations
 *       can render on the client without lossy translation through an
 *       OpenAI-shaped proxy.</li>
 *   <li>You are deploying Command A+ (or any Cohere model) on customer
 *       infrastructure (e.g. 2× H100, 1× B200) and want to point the
 *       runtime at a sovereign endpoint by overriding
 *       {@code cohere.base.url}. The runtime's wire format matches the
 *       published Cohere v2 spec, so a self-hosted Command A+ server that
 *       implements that spec works without translation.</li>
 *   <li>You want native multilingual / multi-modal handling once vision
 *       lands — the SPI carries {@link org.atmosphere.ai.Content.Image}
 *       and the runtime declares the matching capability at that point.
 *       This module ships text + tool-calling first; vision is staged for
 *       the end-of-phase parity pass alongside Anthropic / Spring AI
 *       Alibaba / AgentScope / Semantic Kernel.</li>
 * </ul>
 *
 * @see org.atmosphere.ai.cohere.CohereAgentRuntime
 * @see org.atmosphere.ai.cohere.CohereChatClient
 */
package org.atmosphere.ai.cohere;
