/*
 * Copyright 2012 Jeanfrancois Arcand
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
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.HeaderConfig.LONG_POLLING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;

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
        String transport = req.getHeader(X_ATMOSPHERE_TRANSPORT);

        // Suspend the connection. Could be long-polling, streaming or websocket.
        meteor.suspend(-1, !(transport != null && transport.equalsIgnoreCase(LONG_POLLING_TRANSPORT)));
    }

    public void onBroadcast(AtmosphereResourceEvent event) {
        logger.info("onBroadcast(): {}", event.getMessage());

        // If we are using long-polling, resume the connection as soon as we get an event.
        String transport = event.getResource().getRequest().getHeader(X_ATMOSPHERE_TRANSPORT);
        if (transport != null && transport.equalsIgnoreCase(LONG_POLLING_TRANSPORT)) {
            Meteor meteor = Meteor.lookup(event.getResource().getRequest());

            meteor.removeListener(this);
            meteor.resume();
        }
    }

    public void onSuspend(AtmosphereResourceEvent event) {
        String transport = event.getResource().getRequest().getHeader(X_ATMOSPHERE_TRANSPORT);
        HttpServletRequest req = event.getResource().getRequest();
        logger.info("Suspending the %s response from ip {}:{}",
                new Object[]{transport == null ? WEBSOCKET_TRANSPORT : transport, req.getRemoteAddr(), req.getRemotePort()});
    }

    public void onResume(AtmosphereResourceEvent event) {
        String transport = event.getResource().getRequest().getHeader(X_ATMOSPHERE_TRANSPORT);
        HttpServletRequest req = event.getResource().getRequest();
        logger.info("Resuming the {} response from ip {}:{}",
                new Object[]{transport == null ? WEBSOCKET_TRANSPORT : transport, req.getRemoteAddr(), req.getRemotePort()});
    }

    public void onDisconnect(AtmosphereResourceEvent event) {
        String transport = event.getResource().getRequest().getHeader(X_ATMOSPHERE_TRANSPORT);
        HttpServletRequest req = event.getResource().getRequest();
        logger.info("{} connection dropped from ip {}:{}",
                new Object[]{transport == null ? WEBSOCKET_TRANSPORT : transport, req.getRemoteAddr(), req.getRemotePort()});
    }

    public void onThrowable(AtmosphereResourceEvent event) {
        logger.info("onThrowable()", event.throwable());
    }
}

