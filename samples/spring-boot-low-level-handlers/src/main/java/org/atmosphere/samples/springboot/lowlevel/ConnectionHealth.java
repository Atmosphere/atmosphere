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
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;

/**
 * Per-connection lifecycle, observed at the resource level.
 *
 * <p>{@code @AtmosphereResourceListenerService} installs this for every resource, so the
 * counters cover both the raw and the managed feed without either of them knowing. This is
 * the layer that sees a {@code onThrowable} or a transport-level {@code onClose} that never
 * reaches an annotated {@code @Disconnect} method.</p>
 */
@AtmosphereResourceListenerService
public class ConnectionHealth extends AtmosphereResourceEventListenerAdapter {

    private static final AtomicLong SUSPENDED = new AtomicLong();
    private static final AtomicLong RESUMED = new AtomicLong();
    private static final AtomicLong DISCONNECTED = new AtomicLong();
    private static final AtomicLong THROWN = new AtomicLong();

    public static long suspended() {
        return SUSPENDED.get();
    }

    public static long resumed() {
        return RESUMED.get();
    }

    public static long disconnected() {
        return DISCONNECTED.get();
    }

    public static long thrown() {
        return THROWN.get();
    }

    @Override
    public void onSuspend(AtmosphereResourceEvent event) {
        SUSPENDED.incrementAndGet();
    }

    @Override
    public void onResume(AtmosphereResourceEvent event) {
        RESUMED.incrementAndGet();
    }

    @Override
    public void onDisconnect(AtmosphereResourceEvent event) {
        DISCONNECTED.incrementAndGet();
    }

    @Override
    public void onThrowable(AtmosphereResourceEvent event) {
        // Never swallow: count it and let the frameworks own logging carry the cause.
        THROWN.incrementAndGet();
    }
}
