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
/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain 
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.container;

import org.apache.catalina.CometEvent;
import org.apache.catalina.CometEvent.EventType;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Comet Portable Runtime implementation on top of Tomcat AIO.
 *
 * @author Jeanfrancois Arcand
 */
public class TomcatCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TomcatCometSupport.class);

    public static final String COMET_EVENT = "CometEvent";
    private final static String SUSPENDED = TomcatCometSupport.class.getName() + ".suspended";
    private final Boolean closeConnectionOnInputStream;

    public TomcatCometSupport(AtmosphereConfig config) {
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
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        CometEvent event = (CometEvent) req.getAttribute(COMET_EVENT);

        // Comet is not enabled.
        if (event == null) {
            throw new IllegalStateException(unableToDetectComet());
        }

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
                    req.setAttribute(SUSPENDED, true);
                } catch (UnsupportedOperationException ex) {
                    // TODO: Must implement the same functionality using a Scheduler
                    logger.trace("Warning: CometEvent.setTimeout not supported on this Tomcat instance. " +
                            " [The Tomcat native connector does not support timeouts on asynchronous I/O.]");
                }
                req.setAttribute(SUSPENDED, true);
            } else {
                try {
                    event.close();
                } catch (IllegalStateException ex) {
                    logger.trace("event.close", ex);
                }
            }
        } else if (event.getEventType() == EventType.READ) {
            // Not implemented
        } else if (event.getEventSubType() == CometEvent.EventSubType.CLIENT_DISCONNECT) {

            if (req.getAttribute(SUSPENDED) != null && closeConnectionOnInputStream) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            }

            try {
                event.close();
            } catch (IllegalStateException ex) {
                logger.trace("event.close", ex);
            }        } else if (event.getEventSubType() == CometEvent.EventSubType.TIMEOUT) {

            action = timedout(req, res);
            try {
                event.close();
            } catch (IllegalStateException ex) {
                logger.trace("event.close", ex);
            }
        } else if (event.getEventType() == EventType.ERROR) {
            try {
                event.close();
            } catch (IllegalStateException ex) {
                logger.trace("event.close", ex);
            }
        } else if (event.getEventType() == EventType.END) {
            if (req.resource() != null && req.resource().isResumed()) {
                AtmosphereResourceImpl.class.cast(req.resource()).cancel();
            } else if (req.getAttribute(SUSPENDED) != null && closeConnectionOnInputStream) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            } else {
                try {
                    event.close();
                } catch (IllegalStateException ex) {
                    logger.trace("event.close", ex);
                }
            }
        }
        return action;
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
                if (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                        || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                    try {
                        event.close();
                    } catch (IllegalStateException ex) {
                        logger.trace("event.close", ex);
                    }
                }
            } catch (IOException ex) {
                logger.debug("action failed", ex);
            }
        }
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            CometEvent event = (CometEvent) req.getAttribute(COMET_EVENT);
            if (event == null) return action;
            try {
                event.close();
            } catch (IllegalStateException ex) {
                logger.trace("event.close", ex);
            }
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
        sb.append("\nIf that's not the case, you can also remove META-INF/context.xml and WEB-INF/lib/atmosphere-compat-tomcat.jar");
        return sb.toString();
    }
}
