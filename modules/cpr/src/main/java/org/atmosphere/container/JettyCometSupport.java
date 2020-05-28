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
import org.mortbay.util.ajax.Continuation;
import org.mortbay.util.ajax.ContinuationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Comet Portable Runtime implementation on top of Jetty's Continuation.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(JettyCometSupport.class);

    private final ConcurrentLinkedQueue<Continuation> resumed = new ConcurrentLinkedQueue<Continuation>();

    public JettyCometSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse response)
            throws IOException, ServletException {
        Continuation c = ContinuationSupport.getContinuation(req, null);
        Action action = null;

        if (!c.isResumed() && !c.isPending() && req.getAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION) == null) {
            // This will throw an exception
            action = suspended(req, response);
            if (action.type() == Action.TYPE.SUSPEND) {
                // Do nothing except setting the times out
                if (action.timeout() != -1) {
                    c.suspend(action.timeout());
                } else {
                    c.suspend(0);
                }
            } else if (action.type() == Action.TYPE.RESUME) {
                if (!resumed.remove(c)) {
                    c.reset();

                    if (req.getAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT) == null) {
                        timedout(req, response);
                    } else {
                        resumed(req, response);
                    }
                }
            }
        } else {
            if (!resumed.remove(c) && req.getAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION) == null) {
                c.reset();

                if (req.getAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT) == null) {
                    timedout(req, response);
                } else {
                    resumed(req, response);
                }
            }
        }
        return action;
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.action().type() == Action.TYPE.RESUME && r.isInScope()) {
            Continuation c = ContinuationSupport.getContinuation(r.getRequest(false), null);
            resumed.offer(c);
            complete(r);
        } else {
            try {
                r.getResponse().flushBuffer();
            } catch (IOException e) {
                logger.trace("", e);
            }
        }
    }

    @Override
    public AsyncSupport<AtmosphereResourceImpl> complete(AtmosphereResourceImpl r) {
        Continuation c = ContinuationSupport.getContinuation(r.getRequest(false), null);
        if (!c.isNew()) {
            c.resume();
        } else {
            r.getRequest().setAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION, true);
        }
        return this;
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && (Long) req.getAttribute(MAX_INACTIVE) == -1) {
            Continuation c = ContinuationSupport.getContinuation(req, null);
            if (c != null) {
                c.resume();
            }
        }
        return action;
    }
}
