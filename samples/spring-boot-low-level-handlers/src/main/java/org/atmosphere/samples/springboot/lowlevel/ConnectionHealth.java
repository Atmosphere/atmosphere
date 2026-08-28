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
package org.atmosphere.samples.springboot.lowlevel;

import java.util.concurrent.atomic.AtomicLong;

import org.atmosphere.config.service.AtmosphereResourceListenerService;
import org.atmosphere.cpr.AtmosphereResourceListenerAdapter;

/**
 * Per-connection lifecycle, observed at the resource level.
 *
 * <p>{@code @AtmosphereResourceListenerService} installs this for every resource, so the
 * counters cover both the raw and the managed feed without either of them knowing.</p>
 *
 * <p><strong>Mind the interface.</strong> The processor installs
 * {@link org.atmosphere.cpr.AtmosphereResourceListener} — {@code onSuspended(String)} /
 * {@code onDisconnect(String)}, keyed by uuid. That is NOT
 * {@code AtmosphereResourceEventListener} ({@code onSuspend(AtmosphereResourceEvent)} …),
 * whose adapter has a confusingly similar name. Extending the wrong one compiles, carries
 * the annotation, and installs nothing: the processor's {@code newClassInstance} fails and
 * the exception is swallowed into a warn. The 2026-08-28 sweep found these counters stuck
 * at zero for exactly that reason.</p>
 */
@AtmosphereResourceListenerService
public class ConnectionHealth extends AtmosphereResourceListenerAdapter {

    private static final AtomicLong SUSPENDED = new AtomicLong();
    private static final AtomicLong DISCONNECTED = new AtomicLong();

    public static long suspended() {
        return SUSPENDED.get();
    }

    public static long disconnected() {
        return DISCONNECTED.get();
    }

    @Override
    public void onSuspended(String uuid) {
        SUSPENDED.incrementAndGet();
    }

    @Override
    public void onDisconnect(String uuid) {
        DISCONNECTED.incrementAndGet();
    }
}
