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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide choke point for the {@link DevInspectorRecorder} the
 * {@code AiStreamingSession} decorator chain consults — mirrors
 * {@code CostAccountantHolder}. Defaults to {@link DevInspectorRecorder#NOOP} so
 * the inspector is off (zero overhead, no capture) until an opt-in bean installs
 * a real recorder.
 */
public final class DevInspectorRecorderHolder {

    private static final AtomicReference<DevInspectorRecorder> HOLDER =
            new AtomicReference<>(DevInspectorRecorder.NOOP);

    private DevInspectorRecorderHolder() {
    }

    public static void install(DevInspectorRecorder recorder) {
        HOLDER.set(Objects.requireNonNull(recorder, "recorder"));
    }

    public static void reset() {
        HOLDER.set(DevInspectorRecorder.NOOP);
    }

    public static DevInspectorRecorder get() {
        return HOLDER.get();
    }
}
