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
package org.atmosphere.container;

import org.apache.catalina.comet.CometEvent;
import org.apache.catalina.comet.CometEvent.EventType;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Comet Portable Runtime implementation on top of Tomcat AIO.
 *
 * @author Jeanfrancois Arcand
 */
public class Tomcat7CometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(Tomcat7CometSupport.class);

    public static final String COMET_EVENT = "CometEvent";

    private static final IllegalStateException unableToDetectComet
            = new IllegalStateException(unableToDetectComet());

    // Client disconnection is broken on Tomcat.
    private final ConcurrentLinkedQueue<CometEvent> resumed
            = new ConcurrentLinkedQueue<CometEvent>();

    public Tomcat7CometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * Invoked by the Tomcat AIO when a Comet request gets detected.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link javax.servlet.http.HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        CometEvent event = (CometEvent) req.getAttribute(COMET_EVENT);

        // Comet is not enabled.
        if (event == null) {
            throw unableToDetectComet;
        }

        Action action = null;
        // For now, we are just interested in CometEvent.READ
        if (event.getEventType() == EventType.BEGIN) {
            action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                logger.debug("Suspending response: {}", res);

                // Do nothing except setting the times out
                try {
                    if (action.timeout != -1) {
                        event.setTimeout((int) action.timeout);
                    } else {
                        event.setTimeout(Integer.MAX_VALUE);
                    }
                } catch (UnsupportedOperationException ex) {
                    // Swallow s Tomcat APR isn't supporting time out
                    // TODO: Must implement the same functionality using a Scheduler
                }
            } else if (action.type == Action.TYPE.RESUME) {
                logger.debug("Resuming response: {}", res);
                event.close();
            } else {
                event.close();
            }
        } else if (event.getEventType() == EventType.READ) {
            // Not implemented
        } else if (event.getEventSubType() == CometEvent.EventSubType.CLIENT_DISCONNECT) {
            logger.debug("Client closed connection: response: {}", res);

            if (!resumed.remove(event)) {
                logger.debug("Client closed connection: response: {}", res);
                action = cancelled(req, res);
            } else {
                logger.debug("Cancelling response: {}", res);
            }

            event.close();
        } else if (event.getEventSubType() == CometEvent.EventSubType.TIMEOUT) {
            logger.debug("Timing out response: {}", res);

            action = timedout(req, res);
            event.close();
        } else if (event.getEventType() == EventType.ERROR) {
            event.close();
        } else if (event.getEventType() == EventType.END) {
            if (!resumed.remove(event)) {
                logger.debug("Client closed connection: response: {}", res);
                action = cancelled(req, res);
            } else {
                logger.debug("Cancelling response: {}", res);
            }

            event.close();
        }
        return action;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl resource) {
        super.action(resource);
        if (resource.action().type == Action.TYPE.RESUME && resource.isInScope()) {
            try {
                CometEvent event = (CometEvent) resource.getRequest().getAttribute(COMET_EVENT);
                if (event == null) return;
                resumed.offer(event);

                // Resume without closing the underlying suspended connection.
                if (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                        || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                    event.close();
                }
            } catch (IOException ex) {
                logger.debug("action failed", ex);
            }
        }
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            CometEvent event = (CometEvent) req.getAttribute(COMET_EVENT);
            if (event == null) return action;
            resumed.offer(event);
            event.close();
        }
        return action;
    }

    /**
     * Tomcat was unable to detect Atmosphere's CometProcessor implementation.
     *
     * @return an error message describing how to fix the issue.
     */
    private static String unableToDetectComet() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tomcat failed to detect this is a Comet application because context.xml ");
        sb.append("is missing or the Http11NioProtocol Connector is not enabled.");
        sb.append("\nIf that's not the case, you can also remove META-INF/context.xml and WEB-INF/lib/atmosphere-compat-tomcat.jar");
        return sb.toString();
    }
}
