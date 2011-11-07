/*
 * Copyright 2011 Jeanfrancois Arcand
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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.Meteor;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple PubSub resource that demonstrate many functionality supported by
 * Atmosphere JQuery Plugin and AtmosphereHandler extension.  You can compare that implementation
 * with the MeteorPubSub and the JQueryPubsub sample
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereHandlerPubSub extends AbstractReflectorAtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> r) throws IOException {

        HttpServletRequest req = r.getRequest();
        HttpServletResponse res = r.getResponse();
        String method = req.getMethod();

        // Suspend the response.
        if ("GET".equalsIgnoreCase(method)) {
            String trackingId = trackingId(req);


            // Log all events on the console, including WebSocket events.
            r.addEventListener(new WebSocketEventListenerAdapter());

            // In case we would have tracked instance of Meteor
            //meteors.put(trackingId, m);

            res.setContentType("text/html;charset=ISO-8859-1");

            Broadcaster b = lookupBroadcaster(req.getPathInfo());
            r.setBroadcaster(b);

            if (req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
                req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, Boolean.TRUE);
                r.suspend(-1, false);
            } else {
                r.suspend(-1);
            }
        } else if ("POST".equalsIgnoreCase(method)) {
            Broadcaster b = lookupBroadcaster(req.getPathInfo());

            String message = req.getReader().readLine();

            if (message != null && message.indexOf("message") != -1) {
                // We could also have looked up the Broadcaster using the Meteor
                // m.getBroadcaster().broadcast(message.substring("message=".length()));
                b.broadcast(message.substring("message=".length()));
            }
        }
    }

    public void destroy() {
    }

    /**
     * Return the {@link Meteor} instance associated with the HeaderConfig.X_ATMOSPHERE_TRACKING_ID header.
     *
     * @param req the {@link HttpServletRequest}
     * @return the {@link Meteor} instance associated with the HeaderConfig.X_ATMOSPHERE_TRACKING_ID header.
     */
    String trackingId(HttpServletRequest req) {
        String trackingId = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) != null ?
                req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) : req.getParameter(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        return trackingId;
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
