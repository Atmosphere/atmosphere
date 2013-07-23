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
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * This class gets used when the {@link org.atmosphere.cpr.AtmosphereFramework} detect the container
 * detect Servlet 3.0 Asynch API.
 *
 * @author Jeanfrancois Arcand
 */
public class Servlet30CometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(Servlet30CometSupport.class);

    public Servlet30CometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * Return "javax.servlet".
     *
     * @return "javax.servlet"
     */
    @Override
    public String getContainerName() {
        return super.getContainerName() + " using javax.servlet/3.0";
    }

    /**
     * {@inheritDoc}
     */
    public Action service(AtmosphereRequest request, AtmosphereResponse response)
            throws IOException, ServletException {

        Action action = suspended(request, response);
        if (action.type() == Action.TYPE.SUSPEND) {
            suspend(action, request, response);
        } else if (action.type() == Action.TYPE.RESUME) {

            Action nextAction = resumed(request, response);
            if (nextAction.type() == Action.TYPE.SUSPEND) {
                suspend(action, request, response);
            }
        }

        return action;
    }

    /**
     * Suspend the connection by invoking {@link AtmosphereRequest#startAsync()}
     *
     * @param action The {@link org.atmosphere.cpr.Action}
     * @param req    the {@link AtmosphereRequest}
     * @param res    the {@link AtmosphereResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    private void suspend(Action action, AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        if (!req.isAsyncStarted() && !Utils.webSocketEnabled(req)) {
            AsyncContext asyncContext = req.startAsync(req, res);
            asyncContext.addListener(new CometListener(this));
            // Do nothing except setting the times out
            if (action.timeout() != -1) {
                asyncContext.setTimeout(action.timeout());
            } else {
                // Jetty 8 does something really weird if you set it to
                // Long.MAX_VALUE, which is to resume automatically.
                asyncContext.setTimeout(Integer.MAX_VALUE);
            }
            req.setAttribute(FrameworkConfig.ASYNC_CONTEXT, asyncContext);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl actionEvent) {
        super.action(actionEvent);
        if (actionEvent.action().type() == Action.TYPE.RESUME && actionEvent.isInScope()) {
            AsyncContext asyncContext =
                    (AsyncContext) actionEvent.getRequest().getAttribute(FrameworkConfig.ASYNC_CONTEXT);

            if (asyncContext != null && (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                    || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false"))) {
                try {
                    asyncContext.complete();
                } catch (IllegalStateException ex) {
                    // Alresady completed.
                    logger.trace("Already resumed!", ex);
                }
            }
        } else if (!actionEvent.isInScope()) {
            logger.trace("Already resumed or cancelled: event: {}", actionEvent);
        }
    }

    /**
     * Servlet 3.0 async listener support.
     */
    private final static class CometListener implements AsyncListener {

        private final AsynchronousProcessor p;

        // For JBoss 7 https://github.com/Atmosphere/atmosphere/issues/240
        public CometListener() {
            p = null;
        }

        public CometListener(AsynchronousProcessor processor) {
            this.p = processor;
        }

        public void onComplete(AsyncEvent event) throws IOException {
            // Jetty 9.0.3 error: https://gist.github.com/jfarcand/5628129
            try {
                logger.trace("Resumed (completed): event: {}", event.getAsyncContext().getRequest());
            } catch (NullPointerException ex) {
            }
        }

        public void onTimeout(AsyncEvent event) throws IOException {
            logger.trace("onTimeout(): event: {}", event.getAsyncContext().getRequest());

            if (p == null) {
                logger.error("Invalid state - CometListener");
                return;
            }

            try {
                p.timedout((AtmosphereRequest) event.getAsyncContext().getRequest(),
                        (AtmosphereResponse) event.getAsyncContext().getResponse());
            } catch (ServletException ex) {
                logger.warn("onTimeout(): failed timing out comet response: " + event.getAsyncContext().getResponse(), ex);
            }
        }

        public void onError(AsyncEvent event) {
            logger.trace("onError(): event: {}", event.getAsyncContext().getResponse());

            if (p == null) {
                logger.error("Invalid state - CometListener");
                return;
            }

            try {
                p.cancelled((AtmosphereRequest) event.getAsyncContext().getRequest(),
                        (AtmosphereResponse) event.getAsyncContext().getResponse());
            } catch (Throwable ex) {
                logger.warn("failed cancelling comet response: " + event.getAsyncContext().getResponse(), ex);
            }
        }

        public void onStartAsync(AsyncEvent event) {
            logger.trace("onStartAsync(): event: {}", event.getAsyncContext().getResponse());
        }
    }

}
