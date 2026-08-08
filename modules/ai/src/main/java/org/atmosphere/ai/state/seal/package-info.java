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
 * Opt-in cryptographic sealing for the file-backed agent state.
 *
 * <p>{@link org.atmosphere.ai.state.seal.AgentStateIntegrity} is the Ed25519
 * seal/verify primitive; {@link org.atmosphere.ai.state.seal.AgentStateSealer}
 * wires it into {@link org.atmosphere.ai.state.FileSystemAgentState} — seal
 * on every save, verify on every load, fail closed on mismatch — once an
 * operator sets {@code -Datmosphere.ai.state.seal.enabled=true}. Deliberate
 * hand-edits are blessed with the
 * {@link org.atmosphere.ai.state.seal.AgentStateReseal} step. OFF by default:
 * without the flag the state files remain plain, hand-editable Markdown/JSONL
 * with unchanged behavior.</p>
 */
package org.atmosphere.ai.state.seal;
