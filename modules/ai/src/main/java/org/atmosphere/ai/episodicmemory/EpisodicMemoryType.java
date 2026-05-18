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
package org.atmosphere.ai.episodicmemory;

/**
 * Taxonomy for cross-conversation memories an agent persists into a
 * {@link EpisodicMemoryStore}. The categories mirror the long-term memory
 * patterns established by interactive coding agents and surface the same
 * intent dimensions to fleet/coordinator implementations.
 *
 * <ul>
 *   <li>{@link #USER}      — facts about the human (role, preferences,
 *       responsibilities).</li>
 *   <li>{@link #FEEDBACK}  — corrections and reinforcements about how the
 *       agent should approach future work.</li>
 *   <li>{@link #PROJECT}   — facts about the codebase, deliverables, or
 *       in-flight initiatives.</li>
 *   <li>{@link #REFERENCE} — pointers to external systems (URLs, dashboards,
 *       project trackers).</li>
 * </ul>
 */
public enum EpisodicMemoryType {
    USER,
    FEEDBACK,
    PROJECT,
    REFERENCE
}
