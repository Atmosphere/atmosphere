/*
 * Copyright 2015 Async-IO.org
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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * An interceptor that keep track of {@link AtmosphereResource#uuid()} and disable invocation of {@link org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter#onSuspend(org.atmosphere.cpr.AtmosphereResourceEvent)}
 * and {@link org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter#onPreSuspend(org.atmosphere.cpr.AtmosphereResourceEvent)}
 * </p>
 * When used, the onSuspend will be only called ONCE for every transport, when the first request is made.
 *
 * @author Jeanfrancois Arcand
 */
public class SuspendTrackerInterceptor extends AtmosphereInterceptorAdapter {

    private final Set<String> trackedUUID = Collections.synchronizedSet(new HashSet<String>());
    private final Logger logger = LoggerFactory.getLogger(SuspendTrackerInterceptor.class);

    @Override
    public Action inspect(final AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        final AtmosphereRequest request = AtmosphereResourceImpl.class.cast(r).getRequest(false);
        boolean connecting = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) != null && request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID).equals("0");

        if (!connecting && !Utils.pollableTransport(r.transport())) {
            if (!trackedUUID.add(r.uuid())) {
                logger.trace("Blocking {} from suspend", r.uuid());
                AtmosphereResourceImpl.class.cast(r).disableSuspendEvent(true);
            }

            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                    logger.trace("Untracking {}", r.uuid());
                    trackedUUID.remove(r.uuid());
                }

                @Override
                public void onClose(AtmosphereResourceEvent event) {
                    onDisconnect(event);
                }
            });
        }
        return Action.CONTINUE;
    }

    public Set<String> trackedUUID(){
        return trackedUUID;
    }

    @Override
    public String toString() {
        return "UUID Tracking Interceptor";
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }
}
