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
 * Runtime decision for a single tool invocation. Resolved by a
 * {@link ToolPermissionPolicy} ahead of dispatch.
 *
 * <ul>
 *   <li>{@link #ALLOW}   — run the tool without a confirmation gate.</li>
 *   <li>{@link #DENY}    — refuse the invocation; return a cancellation
 *       result to the model and emit a {@code ToolInvocationEvent} with
 *       outcome {@code DENIED}.</li>
 *   <li>{@link #CONFIRM} — route through the {@code ApprovalStrategy}
 *       (parks until a human resolves it), regardless of any
 *       {@code @RequiresApproval} annotation on the tool method.</li>
 * </ul>
 */
public enum ToolPermission {
    ALLOW,
    DENY,
    CONFIRM
}
