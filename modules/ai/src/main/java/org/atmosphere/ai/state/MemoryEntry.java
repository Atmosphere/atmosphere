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
package org.atmosphere.ai.state;

import java.time.Instant;

/**
 * A single memory entry stored in {@link AgentState} — either a durable fact
 * (from {@code MEMORY.md}) or a daily note (from {@code memory/YYYY-MM-DD.md}).
 *
 * <p>The identifier is assigned by the backend when the entry is first stored;
 * it is stable across reads and is the key admin endpoints use for edit and
 * delete operations.</p>
 *
 * @param id        stable identifier assigned by the backend
 * @param content   the text content of the entry
 * @param createdAt when the entry was first recorded
 */
public record MemoryEntry(String id, String content, Instant createdAt) {
}
