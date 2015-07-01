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

package org.atmosphere.container;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.util.ExecutorsFactory;
import org.jboss.servlet.http.HttpEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Comet Portable Runtime implementation on top of Tomcat AIO.
 *
 * @author Jeanfrancois Arcand
 */
public class JBossWebCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(JBossWebCometSupport.class);

    public static final String HTTP_EVENT = "HttpEvent";
    private final static String SUSPENDED = JBossWebCometSupport.class.getName() + ".suspended";

    private static final IllegalStateException unableToDetectComet = new IllegalStateException(unableToDetectComet());
    private final Boolean closeConnectionOnInputStream;

    public JBossWebCometSupport(AtmosphereConfig config) {
        super(config);
        Object b = config.getInitParameter(ApplicationConfig.TOMCAT_CLOSE_STREAM);
        closeConnectionOnInputStream = b == null ? true : Boolean.parseBoolean(b.toString());
        try {
            Class.forName(HttpEvent.class.getName());
        } catch (Throwable e) {
            throw new IllegalStateException(unableToDetectComet());
        }
    }

    /**
     * Invoked by the Tomcat AIO when a Comet request gets detected.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        HttpEvent event = (HttpEvent) req.getAttribute(HTTP_EVENT);

        // Comet is not enabled.
        if (event == null) {
            logger.error("HttpEvent is null, JBoss APR Not Properly installed");
            throw unableToDetectComet;
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Event Type {} for {}", event.getType(), req.getRequestURL().toString());
        }

        Action action = null;
        // For now, we are just interested in HttpEvent.REA
        AtmosphereResource r = req.resource();
        if (event.getType() == HttpEvent.EventType.BEGIN) {
            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
                // Do nothing except setting the times out
                try {
                    if (action.timeout() != -1) {
                        event.setTimeout((int) action.timeout());
                    } else {
                        event.setTimeout(Integer.MAX_VALUE);
                    }
                    req.setAttribute(SUSPENDED, true);
                } catch (UnsupportedOperationException ex) {
                    // Swallow s Tomcat APR isn't supporting time out
                    // TODO: Must implement the same functionality using a Scheduler
                }
            } else if (action.type() == Action.TYPE.RESUME) {
                close(event);
            } else {
                close(event);
            }
        } else if (event.getType() == HttpEvent.EventType.READ) {
            // Not implemented
            logger.debug("Receiving bytes, unable to process them.");
        } else if (event.getType() == HttpEvent.EventType.EOF
                || event.getType() == HttpEvent.EventType.ERROR
                || event.getType() == HttpEvent.EventType.END) {

            if (r != null && r.isResumed()) {
                AtmosphereResourceImpl.class.cast(req.resource()).cancel();
            } else if (req.getAttribute(SUSPENDED) != null && closeConnectionOnInputStream) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            } else {
                close(event);
            }
        } else if (event.getType() == HttpEvent.EventType.TIMEOUT) {
            action = timedout(req, res);
            close(event);
        }
        return action;
    }

    private void close(HttpEvent event) {
        try {
            event.close();
        } catch (Exception ex) {
            logger.trace("event.close", ex);
        }
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            HttpEvent event = (HttpEvent) req.getAttribute(HTTP_EVENT);
            if (event == null) {
                return action;
            }
            close(event);
        }
        return action;
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.action().type() == Action.TYPE.RESUME && r.isInScope()) {
            HttpEvent event = (HttpEvent) r.getRequest(false).getAttribute(HTTP_EVENT);
            if (event != null && !r.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET)) {
                close(event);
            }
        }
    }

    @Override
    public AsyncSupport complete(AtmosphereResourceImpl r) {
        final HttpEvent event = (HttpEvent) r.getRequest(false).getAttribute(HTTP_EVENT);
        // Prevent Deadlock
        // https://github.com/Atmosphere/atmosphere/issues/1782
        if (event != null) {
            if (!r.isResumed()) {
                ExecutorsFactory.getScheduler(config).schedule(new Runnable() {
                    @Override
                    public void run() {
                        close(event);
                    }
                }, 500, TimeUnit.MILLISECONDS);
            } else {
                close(event);
            }
        }
        return this;
    }

    /**
     * Tomcat was unable to detect Atmosphere's CometProcessor implementation.
     *
     * @return an error message describing how to fix the issue.
     */
    private static String unableToDetectComet() {
        StringBuilder sb = new StringBuilder();
        sb.append("JBoss failed to detect this is a Comet application because the APR Connector is not enabled. ");
        sb.append("\nMake sure atmosphere-compat-jboss.jar is not under your WEB-INF/lib and ");
        sb.append("You must use the atmosphere-native-runtime dependency in order to use native Comet Support");
        sb.append("\nthere is no context.xml under WEB-INF");
        return sb.toString();
    }
}
