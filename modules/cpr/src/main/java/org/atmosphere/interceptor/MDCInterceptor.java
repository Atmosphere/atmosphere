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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.MDC;

/**
 * Populates the SLF4J {@link MDC} with {@code atmosphere.uuid},
 * {@code atmosphere.transport}, and {@code atmosphere.broadcaster} for
 * structured logging. Cleared after processing.
 *
 * @since 4.0
 */
public class MDCInterceptor extends AtmosphereInterceptorAdapter {

    public static final String MDC_UUID = "atmosphere.uuid";
    public static final String MDC_TRANSPORT = "atmosphere.transport";
    public static final String MDC_BROADCASTER = "atmosphere.broadcaster";

    @Override
    public Action inspect(AtmosphereResource r) {
        super.inspect(r);

        MDC.put(MDC_UUID, r.uuid());
        if (r.transport() != null) {
            MDC.put(MDC_TRANSPORT, r.transport().name());
        }
        if (r.getBroadcaster() != null) {
            MDC.put(MDC_BROADCASTER, r.getBroadcaster().getID());
        }

        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        MDC.remove(MDC_UUID);
        MDC.remove(MDC_TRANSPORT);
        MDC.remove(MDC_BROADCASTER);
    }

    @Override
    public String toString() {
        return "MDCInterceptor{}";
    }
}
