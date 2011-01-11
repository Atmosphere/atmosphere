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

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.jboss.servlet.http.HttpEvent;
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
public class JBossWebCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(JBossWebCometSupport.class);

    public static final String HTTP_EVENT = "HttpEvent";

    private static final IllegalStateException unableToDetectComet = new IllegalStateException(unableToDetectComet());

    // Client disconnection is broken on Tomcat.
    private final ConcurrentLinkedQueue<HttpEvent> resumed = new ConcurrentLinkedQueue<HttpEvent>();

    public JBossWebCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * Invoked by the Tomcat AIO when a Comet request gets detected.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action service(HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {

        HttpEvent event = (HttpEvent) req.getAttribute(HTTP_EVENT);

        // Comet is not enabled.
        if (event == null) {
            throw unableToDetectComet;
        }

        Action action = null;
        // For now, we are just interested in HttpEvent.REA
        if (event.getType() == HttpEvent.EventType.BEGIN) {
            action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                logger.debug("Suspending response: {}", res);

                // Do nothing except setting the times out
                try {
                    if (action.timeout != -1) {
                        event.setTimeout((int) action.timeout);
                    }
                    else {
                        event.setTimeout(Integer.MAX_VALUE);
                    }
                }
                catch (UnsupportedOperationException ex) {
                    // Swallow s Tomcat APR isn't supporting time out
                    // TODO: Must implement the same functionality using a Scheduler
                }
            }
            else if (action.type == Action.TYPE.RESUME) {
                logger.debug("Resuming response: {}", res);
                event.close();
            }
            else {
                event.close();
            }
        }
        else if (event.getType() == HttpEvent.EventType.READ) {
            // Not implemente
        }
        else if (event.getType() == HttpEvent.EventType.ERROR) {
            event.close();
        }
        else if (event.getType() == HttpEvent.EventType.END) {
            if (!resumed.remove(event)) {
                logger.debug("Client closed connection response: {}", res);
                action = cancelled(req, res);
            }
            else {
                logger.debug("Cancelling response: {}", res);
            }
            event.close();
        }
        else if (event.getType() == HttpEvent.EventType.TIMEOUT) {
            logger.debug("Timing out {}", res);
            action = timedout(req, res);
            event.close();
        }
        return action;
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            HttpEvent event = (HttpEvent) req.getAttribute(HTTP_EVENT);
            if (event == null) {
                return action;
            }
            resumed.offer(event);
            event.close();
        }
        return action;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl actionEvent) {
        super.action(actionEvent);
        if (actionEvent.action().type == Action.TYPE.RESUME && actionEvent.isInScope()) {
            try {
                HttpEvent event = (HttpEvent) actionEvent.getRequest().getAttribute(HTTP_EVENT);
                resumed.offer(event);
                // Resume without closing the underlying suspended connection.
                if (config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE) == null ||
                        config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                    event.close();
                }
            }
            catch (IOException ex) {
                logger.debug("", ex);
            }
        }
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
        sb.append("\nthere is no context.xml under WEB-INF");
        return sb.toString();
    }
}
