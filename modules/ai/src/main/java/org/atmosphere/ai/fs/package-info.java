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
 * The harness FILESYSTEM primitive — a bounded, conversation-scoped virtual
 * filesystem the model reads and writes through tools.
 * {@link org.atmosphere.ai.fs.AgentFileSystem} is the store SPI
 * (ls / read / write / edit / glob / grep / delete / rename with hard
 * {@link org.atmosphere.ai.fs.AgentFileSystem.Limits}); the default
 * {@link org.atmosphere.ai.fs.WorkspaceAgentFileSystem} roots each
 * conversation at {@code files/{conversationId}/} under the agent workspace
 * with strict traversal guards.
 *
 * <p>The portable floor is the built-in eight-tool surface
 * ({@link org.atmosphere.ai.fs.FileSystemTools}); runtimes that genuinely
 * bridge a native file surface to this store declare
 * {@link org.atmosphere.ai.AiCapability#VIRTUAL_FILESYSTEM} and win under
 * the {@link org.atmosphere.ai.fs.FilesystemMode} AUTO default — the
 * built-in tools are then not registered (no duplicate tools).</p>
 */
package org.atmosphere.ai.fs;
