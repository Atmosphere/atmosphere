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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.Meteor;
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
 * Atmosphere JQuery Plugin and Atmosphere Meteor extension.
 *
 * This sample support out of the box WebSocket, Long-Polling and Streaming
 *
 * @author Jeanfrancois Arcand
 */
public class MeteorPubSub extends HttpServlet {

    // Uncomment if you want to track instance of Meteor from request to request using the HeaderConfig.X_ATMOSPHERE_TRACKING_ID header.
    //private ConcurrentHashMap<String, Meteor> meteors = new ConcurrentHashMap<String, Meteor>();

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // Create a Meteor
        Meteor m = Meteor.build(req);

        // Log all events on the console, including WebSocket events.
        m.addListener(new WebSocketEventListenerAdapter());

        String trackingId = trackingId(req);

        // In case we would have tracked instance of Meteor
        //meteors.put(trackingId, m);

        res.setContentType("text/html;charset=ISO-8859-1");

        Broadcaster b = lookupBroadcaster(req.getPathInfo());
        m.setBroadcaster(b);

        if (req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
            req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, Boolean.TRUE);
            m.suspend(-1, false);
        } else {
            m.suspend(-1);
        }

    }

    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // We could have also retrived the Meteor using the tracking ID
        //Meteor m = meteors.get(trackingId(req));
        Broadcaster b = lookupBroadcaster(req.getPathInfo());

        String message = req.getReader().readLine();

        if (message != null && message.indexOf("message") != -1) {
            // We could also have looked up the Broadcaster using the Meteor
            // m.getBroadcaster().broadcast(message.substring("message=".length()));
            b.broadcast(message.substring("message=".length()));
        }
    }

    /**
     * Return the {@link Meteor} instance associated with the HeaderConfig.X_ATMOSPHERE_TRACKING_ID header.
     * @param req the {@link HttpServletRequest}
     * @return  the {@link Meteor} instance associated with the HeaderConfig.X_ATMOSPHERE_TRACKING_ID header.
     */
    String trackingId(HttpServletRequest req) {
        String trackingId = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) != null ?
                req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) : req.getParameter(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        return trackingId;
    }

    /**
     * Retrieve the {@link Broadcaster} based on the request's path info.
     * @param pathInfo
     * @return the {@link Broadcaster} based on the request's path info.
     */
    Broadcaster lookupBroadcaster(String pathInfo) {
        String[] decodedPath = pathInfo.split("/");
        Broadcaster b = BroadcasterFactory.getDefault().lookup(decodedPath[decodedPath.length - 1], true);
        return b;
    }
}
