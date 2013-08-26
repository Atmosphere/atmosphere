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
package org.atmosphere.container;

import org.apache.catalina.comet.CometEvent;
import org.apache.catalina.comet.CometEvent.EventType;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.RequestFacade;
import org.apache.catalina.connector.Response;
import org.apache.catalina.connector.ResponseFacade;
import org.apache.tomcat.util.http.mapper.MappingData;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.Field;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Comet Portable Runtime implementation on top of Tomcat AIO.
 *
 * @author Jeanfrancois Arcand
 */
public class Tomcat7CometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(Tomcat7CometSupport.class);

    public static final String COMET_EVENT = "CometEvent";
    private final static String SUSPENDED = Tomcat7CometSupport.class.getName() + ".suspended";
    private final Boolean closeConnectionOnInputStream;

    private static final IllegalStateException unableToDetectComet
            = new IllegalStateException(unableToDetectComet());

    public Tomcat7CometSupport(AtmosphereConfig config) {
        super(config);
        Object b = config.getInitParameter(ApplicationConfig.TOMCAT_CLOSE_STREAM) ;
        closeConnectionOnInputStream = b == null ? true : Boolean.parseBoolean(b.toString());
        try {
            Class.forName(CometEvent.class.getName());
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
    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        CometEvent event = (CometEvent) req.getAttribute(COMET_EVENT);

        // Comet is not enabled.
        if (event == null) {
            throw unableToDetectComet;
        }

        logger.trace("event {} with request {}", event, req);

        Action action = null;
        // For now, we are just interested in CometEvent.READ
        if (event.getEventType() == EventType.BEGIN) {
            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
                // Do nothing except setting the times out
                try {
                    if (action.timeout() != -1) {
                        event.setTimeout((int) action.timeout());
                    } else {
                        event.setTimeout(Integer.MAX_VALUE);
                    }
                } catch (UnsupportedOperationException ex) {
                    // TODO: Must implement the same functionality using a Scheduler
                    logger.trace("Warning: CometEvent.setTimeout not supported on this Tomcat instance. " +
                            " [The Tomcat native connector does not support timeouts on asynchronous I/O.]");
                }
                req.setAttribute(SUSPENDED, true);
            } else {
                bz51881(event);
            }
        } else if (event.getEventType() == EventType.READ) {
            // Not implemented
        } else if (event.getEventSubType() == CometEvent.EventSubType.CLIENT_DISCONNECT) {

            if (req.getAttribute(SUSPENDED) != null) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            }

            bz51881(event);
        } else if (event.getEventSubType() == CometEvent.EventSubType.TIMEOUT) {
            action = timedout(req, res);
            bz51881(event);
        } else if (event.getEventType() == EventType.ERROR) {
            bz51881(event);
        } else if (event.getEventType() == EventType.END) {
            if (req.resource() != null && req.resource().isResumed()) {
                AtmosphereResourceImpl.class.cast(req.resource()).cancel();
            } else if (req.getAttribute(SUSPENDED) != null && closeConnectionOnInputStream) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            } else {
                bz51881(event);
            }
        }
        return action;
    }

    private void bz51881(CometEvent event) throws IOException {
        String[] tomcatVersion =  config.getServletContext().getServerInfo().substring(14).split("\\.");
        try {
            String minorVersion = tomcatVersion[2];
            if (minorVersion.indexOf("-") != -1) {
                minorVersion = minorVersion.substring(0, minorVersion.indexOf("-"));
                if (Integer.valueOf(minorVersion) == 22) {
                    minorVersion = "23";
                }
            }

            if (Integer.valueOf(tomcatVersion[0]) == 7 && Integer.valueOf(minorVersion) < 23) {
                logger.info("Patching Tomcat 7.0.22 and lower bz51881. Expect NPE inside CoyoteAdapter, just ignore them. Upgrade to 7.0.23");
                try {
                    RequestFacade request = RequestFacade.class.cast(event.getHttpServletRequest());
                    Field coyoteRequest = RequestFacade.class.getDeclaredField("request");
                    coyoteRequest.setAccessible(true);
                    Request r = (Request) coyoteRequest.get(request);
                    r.recycle();

                    Field mappingData = Request.class.getDeclaredField("mappingData");
                    mappingData.setAccessible(true);
                    MappingData m = new MappingData();
                    m.context = null;
                    mappingData.set(r, m);
                } catch (Throwable t) {
                    logger.trace("Was unable to recycle internal Tomcat object");
                } finally {
                    try {
                        event.close();
                    } catch (IllegalStateException e) {
                        logger.trace("", e);
                    }
                }

                try {
                    ResponseFacade response = ResponseFacade.class.cast(event.getHttpServletResponse());
                    Field coyoteResponse = ResponseFacade.class.getDeclaredField("response");
                    coyoteResponse.setAccessible(true);
                    Response r = (Response) coyoteResponse.get(response);
                    r.recycle();
                } catch (Throwable t) {
                    logger.trace("Was unable to recycle internal Tomcat object");
                }
            } else {
                try {
                    event.close();
                } catch (IllegalStateException ex) {
                    logger.trace("event.close", ex);
                }
            }
        } catch (NumberFormatException ex) {
            logger.trace("This is a mofified version of Tomcat {}", config.getServletContext().getServerInfo().substring(14).split("\\."));
            try {
                event.close();
            } catch (IllegalStateException e) {
                logger.trace("event.close", e);
            }
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl resource) {
        super.action(resource);
        if (resource.action().type() == Action.TYPE.RESUME && resource.isInScope()) {
            try {
                CometEvent event = (CometEvent) resource.getRequest().getAttribute(COMET_EVENT);
                if (event == null) return;

                // Resume without closing the underlying suspended connection.
                if (!resource.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET) &&
                        (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                        || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false"))) {
                    bz51881(event);
                }
            } catch (IOException ex) {
                logger.debug("action failed", ex);
            }
        }
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            CometEvent event = (CometEvent) req.getAttribute(COMET_EVENT);
            if (event == null) return action;
            bz51881(event);
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
        sb.append("You must use the atmosphere-native-runtime dependency in order to use native Comet Support");
        sb.append("\nIf that's not the case, you can also remove META-INF/context.xml and WEB-INF/lib/atmosphere-compat-tomcat7.jar");
        return sb.toString();
    }
}
