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

import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.cpr.AsyncSupportListenerAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;

/**
 * The same lifecycle one layer lower — at the transport, not the resource.
 *
 * <p>{@code @AsyncSupportListenerService} sees suspend/resume/timeout as the container
 * performs them. A timeout that never produces an {@code AtmosphereResourceEvent} still
 * shows up here, which is why this is the layer you instrument when connections vanish and
 * the resource-level counters disagree.</p>
 */
@AsyncSupportListenerService
public class TransportHealth extends AsyncSupportListenerAdapter {

    private static final AtomicLong TIMEOUTS = new AtomicLong();
    private static final AtomicLong CLOSES = new AtomicLong();

    public static long timeouts() {
        return TIMEOUTS.get();
    }

    public static long closes() {
        return CLOSES.get();
    }

    @Override
    public void onTimeout(AtmosphereRequest request, AtmosphereResponse response) {
        TIMEOUTS.incrementAndGet();
    }

    @Override
    public void onClose(AtmosphereRequest request, AtmosphereResponse response) {
        CLOSES.incrementAndGet();
    }
}
