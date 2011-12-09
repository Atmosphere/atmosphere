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

import com.sun.grizzly.comet.CometContext;
import com.sun.grizzly.comet.CometEngine;
import com.sun.grizzly.comet.CometEvent;
import com.sun.grizzly.comet.CometHandler;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.config.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
     * Init Grizzly's {@link CometContext} that will be used to suspend and
     * resume the response.
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

    /**
     * {@inheritDoc}
     */
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        CometContext ctx = CometEngine.getEngine().getCometContext(atmosphereCtx);
        Action action = suspended(req, res);
        if (action.type == Action.TYPE.SUSPEND) {
            logger.debug("Suspending response: {}", res);
            suspend(ctx, action, req, res);
        } else if (action.type == Action.TYPE.RESUME) {
            logger.debug("Resuming response: {}", res);

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
    private void suspend(CometContext ctx, Action action, HttpServletRequest req,
                         HttpServletResponse res) {
        VoidCometHandler c = new VoidCometHandler(req, res);
        ctx.setExpirationDelay(action.timeout);
        ctx.addCometHandler(c);
        req.setAttribute(ATMOSPHERE, c.hashCode());
        ctx.addAttribute("Time", System.currentTimeMillis());

        if (supportSession()) {
            // Store as well in the session in case the resume operation
            // happens outside the AtmosphereHandler.onStateChange scope.
            req.getSession().setAttribute(ATMOSPHERE, c.hashCode());
        }
    }

    /**
     * Resume the underlying response,
     *
     * @param req an {@link HttpServletRequest}
     * @param ctx a {@link CometContext}
     */
    private void resume(HttpServletRequest req, CometContext ctx) {

        if (req.getAttribute(ATMOSPHERE) == null) {
            return;
        }

        CometHandler handler = ctx.getCometHandler((Integer) req.getAttribute(ATMOSPHERE));
        req.removeAttribute(ATMOSPHERE);

        if (handler == null && supportSession() && req.getSession(false) != null) {
            handler = ctx.getCometHandler((Integer) req.getSession(false).getAttribute(ATMOSPHERE));
            req.getSession().removeAttribute(ATMOSPHERE);
        }

        if (handler != null && (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false"))) {
            ctx.resumeCometHandler(handler);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.action().type == Action.TYPE.RESUME && r.isInScope()) {
            CometContext ctx = CometEngine.getEngine().getCometContext(atmosphereCtx);
            resume(r.getRequest(), ctx);
        }
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        Action action = super.cancelled(req, res);
        if (req.getAttribute(MAX_INACTIVE) != null && Long.class.cast(req.getAttribute(MAX_INACTIVE)) == -1) {
            resume(req, CometEngine.getEngine().getCometContext(atmosphereCtx));
        }
        return action;
    }

    /**
     * Void {@link CometHandler}, which delegate the processing of the
     * {@link HttpServletResponse} to an {@link org.atmosphere.cpr.AtmosphereResourceImpl}.
     */
    private class VoidCometHandler implements CometHandler {

        HttpServletRequest req;
        HttpServletResponse res;

        public VoidCometHandler(HttpServletRequest req, HttpServletResponse res) {
            this.req = req;
            this.res = res;
        }

        /**
         * {@inheritDoc}
         */
        public void attach(Object o) {
        }

        /**
         * {@inheritDoc}
         */
        public void onEvent(CometEvent ce) throws IOException {
        }

        /**
         * {@inheritDoc}
         */
        public void onInitialize(CometEvent ce) throws IOException {
        }

        /**
         * {@inheritDoc}
         */
        public void onTerminate(CometEvent ce) throws IOException {
        }

        /**
         * {@inheritDoc}
         */
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
