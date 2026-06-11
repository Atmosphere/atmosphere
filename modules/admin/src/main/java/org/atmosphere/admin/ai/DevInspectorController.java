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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.devinspector.DevInspectorEntry;
import org.atmosphere.ai.devinspector.DevInspectorRecorder;

import java.util.List;

/**
 * Read surface for the inner-loop dev inspector — backs the admin console's
 * "Dev" tab. Exposes the most recent recorded AI turns (prompt / response /
 * tool-calls / usage) captured on the live streaming path.
 *
 * <p><strong>Dev-only.</strong> Prompts and responses may contain sensitive
 * data, so the recorder is installed only behind an explicit opt-in and these
 * reads are served inside the authenticated admin surface.</p>
 */
public final class DevInspectorController {

    private static final int DEFAULT_LIMIT = 50;

    private final DevInspectorRecorder recorder;

    public DevInspectorController(DevInspectorRecorder recorder) {
        this.recorder = recorder != null ? recorder : DevInspectorRecorder.NOOP;
    }

    /** Most-recent-first recorded turns, capped at {@code limit} (default 50). */
    public List<DevInspectorEntry> recent(int limit) {
        return recorder.recent(limit > 0 ? limit : DEFAULT_LIMIT);
    }

    /** Number of retained turns. */
    public int size() {
        return recorder.size();
    }

    /** Drop all retained turns. Mutating — gate behind admin write auth. */
    public void clear() {
        recorder.clear();
    }
}
