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
package org.atmosphere.samples.pubsub;

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;

import java.io.IOException;

import static org.atmosphere.cpr.HeaderConfig.JSONP_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.LONG_POLLING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;

/**
 * Simple PubSub resource that demonstrate many functionality supported by
 * Atmosphere JQuery Plugin and AtmosphereHandler extension.  You can compare that implementation
 * with the MeteorPubSub and the JQueryPubsub sample
 * <br/>
 * This sample support out of the box WebSocket, JSONP, Long-Polling and Streaming
 * <br/>
 * This sample is for demonstration purpose only. It is recommended to install the
 * {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor} and
 * {@link org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor} to
 * significantly reduce the complexity of the code.
 *
 * @author Jeanfrancois Arcand
 */
@AtmosphereHandlerService
public class AtmosphereHandlerPubSub extends AbstractReflectorAtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource r) throws IOException {

        AtmosphereRequest req = r.getRequest();
        AtmosphereResponse res = r.getResponse();
        String method = req.getMethod();

        // Suspend the response.
        if ("GET".equalsIgnoreCase(method)) {
            // Log all events on the console, including WebSocket events.
            r.addEventListener(new WebSocketEventListenerAdapter());

            res.setContentType("text/html;charset=ISO-8859-1");

            Broadcaster b = lookupBroadcaster(req.getPathInfo());
            r.setBroadcaster(b);

            // All of  this logic can be transparently done using the AtmosphereResourceLifecycleInterceptor
            if (req.getHeader(X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(LONG_POLLING_TRANSPORT)
                    || req.getHeader(X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(JSONP_TRANSPORT)) {
                r.resumeOnBroadcast(true);
                r.suspend(-1, false);
            } else {
                r.suspend(-1);
            }
        } else if ("POST".equalsIgnoreCase(method)) {
            Broadcaster b = lookupBroadcaster(req.getPathInfo());

            // All of  this logic can be transparently done using the BroadcastOnPostAtmosphereInterceptor
            String message = req.getReader().readLine();
            if (message != null && message.indexOf("message") != -1) {
                b.broadcast(message.substring("message=".length()));
            }
        }
    }

    @Override
    public void destroy() {
    }

    /**
     * Retrieve the {@link Broadcaster} based on the request's path info.
     *
     * @param pathInfo
     * @return the {@link Broadcaster} based on the request's path info.
     */
    Broadcaster lookupBroadcaster(String pathInfo) {
        String[] decodedPath = pathInfo.split("/");
        Broadcaster b = BroadcasterFactory.getDefault().lookup(decodedPath[decodedPath.length - 1], true);
        return b;
    }

}
