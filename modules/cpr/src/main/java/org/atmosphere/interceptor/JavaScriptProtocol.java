/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.client.TrackMessageSizeFilter;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.HeaderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An Interceptor that send back to a websocket and http client the value of {@link HeaderConfig#X_ATMOSPHERE_TRACKING_ID}
 * and {@link HeaderConfig#X_CACHE_DATE}
 *
 * @author Jeanfrancois Arcand
 */
public class JavaScriptProtocol implements AtmosphereInterceptor {
    private final static Logger logger = LoggerFactory.getLogger(JavaScriptProtocol.class);
    private String wsDelimiter = "|";
    private final TrackMessageSizeFilter f = new TrackMessageSizeFilter();

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.MESSAGE_DELIMITER);
        if (s != null) {
            wsDelimiter = s;
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        String uuid = r.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        String handshakeUUID = r.getRequest().getHeader(HeaderConfig.X_ATMO_PROTOCOL);
        if (uuid != null && uuid.equals("0") && handshakeUUID != null) {
            r.getRequest().header(HeaderConfig.X_ATMO_PROTOCOL, null);
            // Since 1.0.10

            final StringBuffer message = new StringBuffer(r.uuid()).append(wsDelimiter).append(System.currentTimeMillis());

            // https://github.com/Atmosphere/atmosphere/issues/993
            boolean track = false;
            if (r.getBroadcaster().getBroadcasterConfig().hasFilters()) {
                for (BroadcastFilter bf : r.getBroadcaster().getBroadcasterConfig().filters()) {
                    if (TrackMessageSizeFilter.class.isAssignableFrom(bf.getClass())) {
                        track = true;
                        break;
                    }
                }
            }

            // https://github.com/Atmosphere/atmosphere/issues/1119
            AtmosphereResourceImpl.class.cast(r).cors();

            final AtomicReference<String> protocolMessage = new AtomicReference<String>(message.toString());
            if (track) {
                protocolMessage.set((String) f.filter(r, protocolMessage.get(), protocolMessage.get()).message());
            }

            if (r.transport() != AtmosphereResource.TRANSPORT.LONG_POLLING && r.transport() != AtmosphereResource.TRANSPORT.JSONP) {
                r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                    @Override
                    public void onSuspend(AtmosphereResourceEvent event) {
                        r.getResponse().write(protocolMessage.get());
                        try {
                            r.getResponse().flushBuffer();
                        } catch (IOException e) {
                            logger.trace("", e);
                        }
                    }
                });
            } else {
                r.getResponse().write(protocolMessage.get());
            }

            // We don't need to reconnect here
            if (r.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET
                    || r.transport() == AtmosphereResource.TRANSPORT.STREAMING
                    || r.transport() == AtmosphereResource.TRANSPORT.SSE) {
                return Action.CONTINUE;
            } else {
                return Action.SKIP_ATMOSPHEREHANDLER;
            }
        }
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
    }

    @Override
    public String toString() {
        return "Atmosphere JavaScript Protocol";
    }
}