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
package org.atmosphere.ai.devinspector;

import java.util.List;

/**
 * Sink for {@link DevInspectorEntry} records captured on the live streaming
 * path. The default {@link #NOOP} discards everything (and the capture decorator
 * is not even wrapped) so the inspector has zero cost until explicitly enabled.
 */
public interface DevInspectorRecorder {

    /** Record a completed turn. Must not throw into the caller's stream. */
    void record(DevInspectorEntry entry);

    /** Most-recent-first view of up to {@code limit} recorded turns. */
    List<DevInspectorEntry> recent(int limit);

    /** Number of retained entries. */
    int size();

    /** Drop all retained entries. */
    void clear();

    /** No-op recorder — inspector disabled. */
    DevInspectorRecorder NOOP = new DevInspectorRecorder() {
        @Override public void record(DevInspectorEntry entry) {
        }

        @Override public List<DevInspectorEntry> recent(int limit) {
            return List.of();
        }

        @Override public int size() {
            return 0;
        }

        @Override public void clear() {
        }
    };
}
