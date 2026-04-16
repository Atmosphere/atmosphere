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
package org.atmosphere.ai.identity;

/**
 * Session-scoped permission mode that layers over per-tool
 * {@code @RequiresApproval} gates. The mode defaults to {@link #DEFAULT}
 * (per-tool approval) and can be escalated or relaxed per session.
 *
 * <p>Mode semantics are consulted by {@code ToolExecutionHelper} before
 * running any tool — the mode is the outer gate, per-tool
 * {@code @RequiresApproval} is the inner gate. Default behavior is fail-closed
 * (Correctness Invariant #6).</p>
 */
public enum PermissionMode {

    /**
     * Per-tool {@code @RequiresApproval} behavior. Tools marked as requiring
     * approval prompt the user; others run without interruption. This is the
     * baseline production mode.
     */
    DEFAULT,

    /**
     * Agent produces a plan first and waits for user approval before any
     * tool executes. Analogous to Claude Code's plan mode. Useful when a
     * user wants visibility into what the agent is about to do.
     */
    PLAN,

    /**
     * Auto-approve edit-shaped tools (file writes, patches, edits) but
     * still prompt for sensitive operations (shell, network, deletions).
     * Matches Claude Code's {@code acceptEdits}.
     */
    ACCEPT_EDITS,

    /**
     * Every tool is auto-approved. Use only in trusted, automated contexts.
     * Must be explicitly opted into — the default stays fail-closed.
     */
    BYPASS,

    /**
     * No tool executes. The agent can still respond in text, but any tool
     * invocation is denied. Useful as a safe mode or kill switch.
     */
    DENY_ALL
}
