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

import com.sun.grizzly.comet.CometContext;
import com.sun.grizzly.comet.CometEngine;
import com.sun.grizzly.comet.CometEvent;
import com.sun.grizzly.comet.CometHandler;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Comet Portable Runtime implementation on top of Grizzly 1.5 and up.
 *
 * @author Jeanfrancois Arcand
 */
public class GrizzlyCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(GrizzlyCometSupport.class);

    private static final String ATMOSPHERE = "/atmosphere";

    private String atmosphereCtx = "";

    public GrizzlyCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * Init Grizzly's {@link CometContext} that will be used to suspend and resume the response.
     *
     * @param sc the {@link ServletContext}
     * @throws javax.servlet.ServletException
     */
    @Override
    public void init(ServletConfig sc) throws ServletException {
        super.init(sc);

        atmosphereCtx = sc.getServletContext().getContextPath() + ATMOSPHERE;

        CometEngine cometEngine = CometEngine.getEngine();
        CometContext context = cometEngine.register(atmosphereCtx);
        context.setExpirationDelay(-1);
        logger.debug("Created CometContext for atmosphere context: {}", atmosphereCtx);
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        CometContext ctx = CometEngine.getEngine().getCometContext(atmosphereCtx);
        Action action = suspended(req, res);
        if (action.type() == Action.TYPE.SUSPEND) {
            suspend(ctx, action, req, res);
        } else if (action.type() == Action.TYPE.RESUME) {
            resume(req, ctx);
        }
        return action;
    }

    /**
     * Suspend the response
     *
     * @param ctx
     * @param action
     * @param req
     * @param res
     */
    private void suspend(CometContext ctx, Action action, AtmosphereRequest req, AtmosphereResponse res) {
        VoidCometHandler c = new VoidCometHandler(req, res);
        ctx.setExpirationDelay(action.timeout());
        ctx.addCometHandler(c);
        req.setAttribute(ATMOSPHERE, c.hashCode());
        ctx.addAttribute("Time", System.currentTimeMillis());
    }

    /**
     * Resume the underlying response.
     *
     * @param req an {@link AtmosphereRequest}
     * @param ctx a {@link CometContext}
     */
    private void resume(AtmosphereRequest req, CometContext ctx) {

        if (req.getAttribute(ATMOSPHERE) == null) {
            return;
        }

        CometHandler handler = ctx.getCometHandler((Integer) req.getAttribute(ATMOSPHERE));
        req.removeAttribute(ATMOSPHERE);

        if (req.resource() != null) {
            try {
                AtmosphereResourceImpl.class.cast(req.resource()).cancel();
            } catch (IOException e) {
                logger.trace("", e);
            }
        }

        if (handler != null) {
            ctx.resumeCometHandler(handler);
        }
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.action().type() == Action.TYPE.RESUME && r.isInScope()) {
            complete(r);
        }
    }

    @Override
    public AsyncSupport complete(AtmosphereResourceImpl r) {
        CometContext ctx = CometEngine.getEngine().getCometContext(atmosphereCtx);
        resume(r.getRequest(false), ctx);
        return this;
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            resume(req, CometEngine.getEngine().getCometContext(atmosphereCtx));
        }
        return action;
    }

    /**
     * Void {@link CometHandler}, which delegate the processing of the
     * {@link AtmosphereRequest} to an {@link org.atmosphere.cpr.AtmosphereResourceImpl}.
     */
    private class VoidCometHandler implements CometHandler {

        AtmosphereRequest req;
        AtmosphereResponse res;

        public VoidCometHandler(AtmosphereRequest req, AtmosphereResponse res) {
            this.req = req;
            this.res = res;
        }

        @Override
        public void attach(Object o) {
        }

        @Override
        public void onEvent(CometEvent ce) throws IOException {
        }

        @Override
        public void onInitialize(CometEvent ce) throws IOException {
        }

        @Override
        public void onTerminate(CometEvent ce) throws IOException {
        }

        @Override
        public synchronized void onInterrupt(CometEvent ce) throws IOException {
            long timeStamp = (Long) ce.getCometContext().getAttribute("Time");
            try {
                if (ce.getCometContext().getExpirationDelay() > 0
                        && (System.currentTimeMillis() - timeStamp) >= ce.getCometContext().getExpirationDelay()) {
                    timedout(req, res);
                } else {
                    cancelled(req, res);
                }
            } catch (ServletException ex) {
                logger.warn("onInterrupt() encountered exception", ex);
            }
        }
    }
}
