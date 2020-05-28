/*
 * Copyright 2008-2020 Async-IO.org
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
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import java.io.IOException;

/**
 * Comet Portable Runtime implementation on top of Jetty's Continuation.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty7CometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(Jetty7CometSupport.class);

    public Jetty7CometSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(final AtmosphereRequest req, final AtmosphereResponse res) throws IOException, ServletException {
        Action action = null;

        Continuation c = (Continuation) req.getAttribute(Continuation.class.getName());

        if (c == null || c.isInitial()) {
            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND && req.getAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION) == null) {
                c = getContinuation(req);
                req.setAttribute(Continuation.class.getName(), c);

                if (action.timeout() != -1) {
                    c.setTimeout(action.timeout());
                } else {
                    // Jetty 7 does something really weird if you set it to
                    // Long.MAX_VALUE, which is to resume automatically.
                    c.setTimeout(Integer.MAX_VALUE);
                }

                c.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE));
                c.addContinuationListener(new ContinuationListener() {

                    @Override
                    public void onComplete(Continuation continuation) {
                        AtmosphereResourceImpl r = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                        if (r != null) {
                            try {
                                r.cancel();
                            } catch (IOException e) {
                                logger.trace("", e);
                            }
                        }
                    }

                    @Override
                    public void onTimeout(Continuation continuation) {
                        AtmosphereResourceImpl r = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                        if (r != null) {
                            try {
                                timedout(r.getRequest(), r.getResponse());
                            } catch (Throwable t) {
                                logger.error("", t);
                            }
                        } else {
                            logger.trace("AtmosphereResource was null");
                        }
                        try {
                            continuation.complete();
                        } catch (Throwable t) {
                            logger.trace("", t);
                        }
                    }
                });

                if (req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE) != null) {
                    c.suspend(res);
                }
            } else if (action.type() == Action.TYPE.RESUME) {
                // If resume occurs during a suspend operation, stop processing.
                Boolean resumeOnBroadcast = (Boolean) req.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
                if (resumeOnBroadcast != null && resumeOnBroadcast) {
                    return action;
                }

                c = (Continuation) req.getAttribute(Continuation.class.getName());
                if (c != null) {
                    if (c.isSuspended()) {
                        try {
                            c.complete();
                        } catch (IllegalStateException ex) {
                            logger.trace("Continuation.complete()", ex);
                        } finally {
                            resumed(req, res);
                        }
                    }
                }
            }
        }
        return action;
    }

    protected Continuation getContinuation(AtmosphereRequest req) {
        return ContinuationSupport.getContinuation(req);
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        complete(r);
    }

    @Override
    public AsyncSupport<AtmosphereResourceImpl> complete(AtmosphereResourceImpl r) {
        ServletRequest request = r.getRequest(false);
        while (request != null) {
            Continuation c = (Continuation) request.getAttribute(Continuation.class.getName());
            if (c != null) {
                try {
                    if (c.isSuspended()) {
                        c.complete();
                    }
                } catch (IllegalStateException ex) {
                    logger.trace("c.complete()", ex);
                } finally {
                    r.getRequest(false).setAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION, true);
                }
                request.removeAttribute(Continuation.class.getName());
            }
            return this;
        }
        return this;
    }
}
