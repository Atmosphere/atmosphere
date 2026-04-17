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
 * Atmosphere AI agent state primitives — conversation history, durable facts,
 * daily notes, working memory, and hierarchical rules — unified under a single
 * {@link org.atmosphere.ai.state.AgentState} SPI.
 *
 * <p>The file-backed default ({@link org.atmosphere.ai.state.FileSystemAgentState})
 * is OpenClaw-compatible: it reads and writes the same {@code AGENTS.md /
 * SOUL.md / USER.md / IDENTITY.md / MEMORY.md / memory/YYYY-MM-DD.md} layout
 * that OpenClaw uses, plus JSONL session transcripts under
 * {@code agents/&lt;agentId&gt;/sessions/&lt;sessionId&gt;.jsonl}.</p>
 *
 * <p>Auto-memory behavior is pluggable via
 * {@link org.atmosphere.ai.state.AutoMemoryStrategy}; four built-ins ship
 * out of the box.</p>
 */
package org.atmosphere.ai.state;
