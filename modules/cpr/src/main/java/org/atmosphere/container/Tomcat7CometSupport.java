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
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.RequestFacade;
import org.apache.catalina.connector.Response;
import org.apache.catalina.connector.ResponseFacade;
import org.apache.tomcat.util.http.mapper.MappingData;
import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;

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
                    req.setAttribute(SUSPENDED, true);
                } catch (UnsupportedOperationException ex) {
                    // Swallow s Tomcat APR isn't supporting time out
                    // TODO: Must implement the same functionality using a Scheduler
                }
            } else if (action.type == Action.TYPE.RESUME) {
                logger.debug("Resuming response: {}", res);
                bz51881(event);
            } else {
                bz51881(event);
            }
        } else if (event.getEventType() == EventType.READ) {
            // Not implemented
        } else if (event.getEventSubType() == CometEvent.EventSubType.CLIENT_DISCONNECT) {
            logger.debug("Client closed connection: response: {}", res);

            if (req.getAttribute(SUSPENDED) != null) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            } else {
                logger.debug("Cancelling response: {}", res);
            }

            bz51881(event);
        } else if (event.getEventSubType() == CometEvent.EventSubType.TIMEOUT) {
            logger.debug("Timing out response: {}", res);

            action = timedout(req, res);
            bz51881(event);
        } else if (event.getEventType() == EventType.ERROR) {
            bz51881(event);
        } else if (event.getEventType() == EventType.END) {
            if (req.getAttribute(SUSPENDED) != null && closeConnectionOnInputStream) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            } else {
                logger.debug("Cancelling response: {}", res);
                bz51881(event);
            }
        }
        return action;
    }

    private void bz51881(CometEvent event) throws IOException {
        String[] tomcatVersion = config.getServletContext().getServerInfo().substring(14).split("\\.");
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
            event.close();
        }
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

                // Resume without closing the underlying suspended connection.
                if (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                        || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                    bz51881(event);
                }
            } catch (IOException ex) {
                logger.debug("action failed", ex);
            }
        }
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {

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
        sb.append("\nIf that's not the case, you can also remove META-INF/context.xml and WEB-INF/lib/atmosphere-compat-tomcat.jar");
        return sb.toString();
    }
}