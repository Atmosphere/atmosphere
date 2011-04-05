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
package org.atmosphere.samples.wicket;

import org.apache.wicket.markup.html.WebPage;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.Meteor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Suspend the response using the {@link Meteor} API.
 *
 * @author Andrey Belyaev
 * @author Jeanfrancois Arcand
 */
public class PushPage extends WebPage implements AtmosphereResourceEventListener {

    private static final Logger logger = LoggerFactory.getLogger(PushPage.class);

    // TODO is this necessary??
    private final AtomicBoolean scheduleStarted = new AtomicBoolean(false);

    public PushPage() {
        HttpServletRequest req = getWebRequestCycle().getWebRequest().getHttpServletRequest();

        // Grap a Meteor
        Meteor meteor = Meteor.build(req);

        // Start scheduling update.
        if (!scheduleStarted.getAndSet(true)) {
            meteor.schedule(new Callable<String>() {
                public String call() {
                    String s = new Date().toString();
                    return s;
                }
            }, 1); // One second
        }

        // Add us to the listener list.
        meteor.addListener(this);

        // Depending on the connection
        String transport = req.getHeader("X-Atmosphere-Transport");

        // Suspend the connection. Could be long-polling, streaming or websocket.
        meteor.suspend(-1, !(transport != null && transport.equalsIgnoreCase("long-polling")));
    }

    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        logger.info("onBroadcast(): {}", event.getMessage());

        // If we are using long-polling, resume the connection as soon as we get an event.
        String transport = event.getResource().getRequest().getHeader("X-Atmosphere-Transport");
        if (transport != null && transport.equalsIgnoreCase("long-polling")) {
            Meteor meteor = Meteor.lookup(event.getResource().getRequest());

            meteor.removeListener(this);
            meteor.resume();
        }
    }

    public void onSuspend(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        String transport = event.getResource().getRequest().getHeader("X-Atmosphere-Transport");
        HttpServletRequest req = event.getResource().getRequest();
        logger.info("Suspending the %s response from ip {}:{}",
                new Object[]{transport == null ? "websocket" : transport, req.getRemoteAddr(), req.getRemotePort()});
    }

    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        String transport = event.getResource().getRequest().getHeader("X-Atmosphere-Transport");
        HttpServletRequest req = event.getResource().getRequest();
        logger.info("Resuming the {} response from ip {}:{}",
                new Object[]{transport == null ? "websocket" : transport, req.getRemoteAddr(), req.getRemotePort()});
    }

    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        String transport = event.getResource().getRequest().getHeader("X-Atmosphere-Transport");
        HttpServletRequest req = event.getResource().getRequest();
        logger.info("{} connection dropped from ip {}:{}",
                new Object[]{transport == null ? "websocket" : transport, req.getRemoteAddr(), req.getRemotePort()});
    }

    public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        logger.info("onThrowable()", event.throwable());
    }
}

