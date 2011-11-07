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
 * @author Jeanfrancois Arcand
 */
public class MeteorPubSub extends HttpServlet {

    private ConcurrentHashMap<String, Meteor> meteors = new ConcurrentHashMap<String, Meteor>();

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Meteor m = Meteor.build(req);

        // Log all events on the console.
        m.addListener(new WebSocketEventListenerAdapter());

        String trackingId = trackingId(req);

        meteors.put(trackingId, m);

        res.setContentType("text/html;charset=ISO-8859-1");

        String[] decodedPath = req.getPathInfo().split("/");
        Broadcaster b = BroadcasterFactory.getDefault().lookup(decodedPath[decodedPath.length - 1], true);
        m.setBroadcaster(b);

        if (req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
            req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, Boolean.TRUE);
            m.suspend(-1, false);
        } else {
            m.suspend(-1);
        }

    }

    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Meteor m = meteors.get(trackingId(req));
        res.setCharacterEncoding("UTF-8");

        String message = req.getReader().readLine();

        if (message != null && message.indexOf("message") != -1) {
            m.getBroadcaster().broadcast(message.substring("message=".length()));
        }
    }

    String trackingId(HttpServletRequest req) {
        String trackingId = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) != null ?
                req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) : req.getParameter(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        return trackingId;
    }
}
