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

import com.sun.grizzly.comet.CometEngine;
import org.apache.catalina.CometEvent;
import org.apache.catalina.CometEvent.EventType;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.cpr.CometSupport;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Comet Portable Runtime implementation on top of Tomcat AIO.
 *
 * @author Jeanfrancois Arcand
 */
public class TomcatCometSupport extends AsynchronousProcessor implements CometSupport<AtmosphereResourceImpl> {

    public final static String COMET_EVENT = "CometEvent";

    private final static IllegalStateException unableToDetectComet
            = new IllegalStateException(unableToDetectComet());

    // Client disconnection is broken on Tomcat.
    private final ConcurrentLinkedQueue<CometEvent> resumed
            = new ConcurrentLinkedQueue<CometEvent>();

    public TomcatCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * Invoked by the Tomcat AIO when a Comet request gets detected.
     *
     * @param req the {@link javax.servlet.http.HttpServletRequest}
     * @param res the {@link HttpServletResponse}
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
        // For now, we are just interested in CometEvent.REA 
        if (event.getEventType() == EventType.BEGIN) {
            action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Suspending " + res);
                }

                // Do nothing except setting the times out
                try {
                    if (action.timeout != -1) {
                        event.setTimeout((int) action.timeout);
                    } else {
                        event.setTimeout(Integer.MAX_VALUE);
                    }
                } catch (UnsupportedOperationException ex) {
                    // Swallow s Tomcat APR isn't supporting time out
                    // TODO: Must implement the same functionality using a
                    // Scheduler
                }
            } else if (action.type == Action.TYPE.RESUME) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Resuming " + res);
                }
                event.close();
            } else {
                event.close();
            }
        } else if (event.getEventType() == EventType.READ) {
            // Not implemented
        } else if (event.getEventSubType() == CometEvent.EventSubType.CLIENT_DISCONNECT) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Client closed connection " + res);
            }
            action = cancelled(req, res);
            event.close();
        } else if (event.getEventSubType() == CometEvent.EventSubType.TIMEOUT) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Timing out " + res);
            }
            action = timedout(req, res);
            event.close();
        } else if (event.getEventType() == EventType.ERROR) {
            event.close();
        } else if (event.getEventType() == EventType.END) {
            if (!resumed.remove(event)) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Client closed connection " + res);
                }
                action = cancelled(req, res);
            } else {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Cancelling " + res);
                }
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
        if (resource.action().type == Action.TYPE.RESUME) {
            try {
                CometEvent event = (CometEvent) resource.getRequest().getAttribute(COMET_EVENT);
                if (event == null) return;
                resumed.offer(event);

                // Resume without closing the underlying suspended connection.
                if (config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE) == null
                        || config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                    event.close();
                }
            } catch (IOException ex) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "", ex);
                }
            }
        }
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        Action action =  super.cancelled(req,res);
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
