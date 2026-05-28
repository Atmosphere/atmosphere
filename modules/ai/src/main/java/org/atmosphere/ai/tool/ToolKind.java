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
package org.atmosphere.ai.tool;

/**
 * The behavioural category of an AI-callable tool, declared by the tool author
 * via {@link org.atmosphere.ai.annotation.AiTool#kind()}.
 *
 * <p>This is <strong>compile-time author metadata</strong>, not runtime
 * caller-declared intent: the developer who writes the tool classifies what it
 * does, the same trust model as the tool body itself. The classification lets
 * the outer {@link org.atmosphere.ai.identity.PermissionMode} make a structured
 * approval decision instead of trusting an LLM's per-call assertion.</p>
 *
 * <p>The only mode that currently keys on this is
 * {@link org.atmosphere.ai.identity.PermissionMode#ACCEPT_EDITS}, which
 * auto-approves {@link #EDIT} tools while still prompting for everything else.
 * The default is {@link #OTHER}, so a tool is never auto-approved unless its
 * author explicitly opts in — the outer gate never silently widens the attack
 * surface.</p>
 */
public enum ToolKind {

    /**
     * Mutates application-owned state in place — file writes, patches, edits.
     * The category {@link org.atmosphere.ai.identity.PermissionMode#ACCEPT_EDITS}
     * auto-approves.
     */
    EDIT,

    /** Read-only inspection — no state change, no side effects. */
    READ,

    /** Executes shell commands or arbitrary code. Sensitive; never auto-approved. */
    EXECUTE,

    /** Performs outbound network calls. Sensitive; never auto-approved. */
    NETWORK,

    /** Destructive removal of data or resources. Sensitive; never auto-approved. */
    DELETE,

    /**
     * Unclassified. The default for every tool that does not declare a
     * {@code kind()} — treated conservatively (no auto-approval).
     */
    OTHER
}
