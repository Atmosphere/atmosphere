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
package org.atmosphere.ai.tape;

/**
 * Filter for {@link TapeStore#listRuns(TapeQuery)}. {@code null} filter
 * fields match everything; {@code limit <= 0} means no explicit limit (the
 * result is still bounded by the store's own {@link TapeStore#maxRuns()}
 * retention). Results are ordered newest-first by {@link TapeRun#startedAt()}.
 *
 * @param tapeId match runs of this conversation key, or {@code null} for any
 * @param status match runs in this status, or {@code null} for any
 * @param limit  maximum rows to return; {@code <= 0} for no explicit limit
 */
public record TapeQuery(String tapeId, TapeStatus status, int limit) {

    /** All runs, up to {@code limit}. */
    public static TapeQuery all(int limit) {
        return new TapeQuery(null, null, limit);
    }

    /** Runs of one conversation key, up to {@code limit}. */
    public static TapeQuery byTapeId(String tapeId, int limit) {
        return new TapeQuery(tapeId, null, limit);
    }

    /** Runs in one status, up to {@code limit}. */
    public static TapeQuery byStatus(TapeStatus status, int limit) {
        return new TapeQuery(null, status, limit);
    }
}
