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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.jboss.servlet.http.HttpEvent;
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
public class JBossWebCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(JBossWebCometSupport.class);

    public static final String HTTP_EVENT = "HttpEvent";
    private final static String SUSPENDED = JBossWebCometSupport.class.getName() + ".suspended";

    private static final IllegalStateException unableToDetectComet = new IllegalStateException(unableToDetectComet());
    private final Boolean closeConnectionOnInputStream;

    public JBossWebCometSupport(AtmosphereConfig config) {
        super(config);
        Object b = config.getInitParameter(ApplicationConfig.TOMCAT_CLOSE_STREAM) ;
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
     * @param req the {@link org.atmosphere.cpr.AtmosphereRequest}
     * @param res the {@link org.atmosphere.cpr.AtmosphereResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        HttpEvent event = (HttpEvent) req.getAttribute(HTTP_EVENT);

        // Comet is not enabled.
        if (event == null) {
            throw unableToDetectComet;
        }

        logger.trace("Event Type {} for {}", event.getType(), req.getQueryString());
        Action action = null;
        // For now, we are just interested in HttpEvent.REA
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
                event.close();
            } else {
                event.close();
            }
        } else if (event.getType() == HttpEvent.EventType.READ) {
            // Not implemented
            logger.debug("Receiving bytes, unable to process them.");
        } else if (event.getType() == HttpEvent.EventType.EOF
                || event.getType() == HttpEvent.EventType.ERROR
                || event.getType() == HttpEvent.EventType.END) {
            if (req.getAttribute(SUSPENDED) != null && closeConnectionOnInputStream) {
                req.setAttribute(SUSPENDED, null);
                action = cancelled(req, res);
            } else {
                event.close();
            }
        } else if (event.getType() == HttpEvent.EventType.TIMEOUT) {
            action = timedout(req, res);
            event.close();
        }
        return action;
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            HttpEvent event = (HttpEvent) req.getAttribute(HTTP_EVENT);
            if (event == null) {
                return action;
            }
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
        if (actionEvent.action().type() == Action.TYPE.RESUME && actionEvent.isInScope()) {
            try {
                HttpEvent event = (HttpEvent) actionEvent.getRequest().getAttribute(HTTP_EVENT);
                // Resume without closing the underlying suspended connection.
                if (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null ||
                        config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                    event.close();
                }
            } catch (IOException ex) {
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
        sb.append("You must use the atmosphere-native-runtime dependency in order to use native Comet Support");
        sb.append("\nthere is no context.xml under WEB-INF");
        return sb.toString();
    }
}
