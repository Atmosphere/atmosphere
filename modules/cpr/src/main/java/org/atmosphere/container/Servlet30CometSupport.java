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
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import java.io.IOException;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * This class is used when the {@link org.atmosphere.cpr.AtmosphereFramework} detect the container
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

    @Override
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
     * Suspend the connection by invoking {@link AtmosphereRequestImpl#startAsync()}
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
            asyncContext.addListener(new CometListener(this, res.uuid()));
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

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.action().type() == Action.TYPE.RESUME && r.isInScope()) {
            endAsyncContext(r.getRequest(false));
        }
    }

    @Override
    public AsyncSupport complete(AtmosphereResourceImpl r) {
        endAsyncContext(r.getRequest(false));
        return null;
    }

    public void endAsyncContext(AtmosphereRequest request){
        final Object attribute = request.getAttribute(FrameworkConfig.ASYNC_CONTEXT);
        if (attribute instanceof AsyncContext) {
            AsyncContext asyncContext = (AsyncContext) attribute;
            if (asyncContext != null) {
                try {
                    asyncContext.complete();
                } catch (IllegalStateException ex) {
                    // Already completed. Jetty throw an exception on shutdown with log
                    try {
                        logger.trace("Already resumed!", ex);
                    } catch (Exception ex2){};
                } finally {
                    request.removeAttribute(FrameworkConfig.ASYNC_CONTEXT);
                }
            }
        }
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            endAsyncContext(req);
        }
        return action;
    }

    /**
     * Servlet 3.0 async listener support.
     */
    private final static class CometListener implements AsyncListener {

        private final AsynchronousProcessor p;
        private final String uuid;

        // For JBoss 7 https://github.com/Atmosphere/atmosphere/issues/240
        public CometListener() {
            this.uuid = "-1";
            p = null;
        }

        public CometListener(AsynchronousProcessor processor, String uuid) {
            this.p = processor;
            this.uuid = uuid;
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            logger.trace("Resumed (completed): event: {}", uuid);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            logger.trace("onTimeout(): event: {}", event.getAsyncContext().getRequest());

            if (p == null) {
                logger.error("Invalid state - CometListener");
                return;
            }

            final AsyncContext asyncContext = event.getAsyncContext();
            try {
                p.timedout((AtmosphereRequest) asyncContext.getRequest(),
                        (AtmosphereResponse) asyncContext.getResponse());
            } catch (ServletException ex) {
                logger.warn("onTimeout(): failed timing out comet response: " + event.getAsyncContext().getResponse(), ex);
            } finally {
                try {
                    asyncContext.complete();
                } catch (IllegalStateException ex) {
                    // The complete method has been already called.
                    // https://tomcat.apache.org/tomcat-7.0-doc/api/org/apache/coyote/AsyncStateMachine.html
                    logger.trace("", ex);
                }
            }
        }

        @Override
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

        @Override
        public void onStartAsync(AsyncEvent event) {
            logger.trace("onStartAsync(): event: {}", event.getAsyncContext().getResponse());
        }
    }
}
