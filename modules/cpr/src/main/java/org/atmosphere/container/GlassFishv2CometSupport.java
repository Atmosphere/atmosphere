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

import com.sun.enterprise.web.connector.grizzly.comet.CometContext;
import com.sun.enterprise.web.connector.grizzly.comet.CometEngine;
import com.sun.enterprise.web.connector.grizzly.comet.CometEvent;
import com.sun.enterprise.web.connector.grizzly.comet.CometHandler;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
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
 * Comet Portable Runtime implementation on top of Grizzly API included with GlassFish v2.
 *
 * @author Jeanfrancois Arcand
 */
public class GlassFishv2CometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(GlassFishv2CometSupport.class);

    private static final String ATMOSPHERE = "/atmosphere";

    private String atmosphereCtx = "";

    public GlassFishv2CometSupport(AtmosphereConfig config) {
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
        ctx.addAttribute("Time", System.currentTimeMillis());
        req.setAttribute(ATMOSPHERE, c.hashCode());

        if (supportSession()) {
            // Store as well in the session in case the resume operation
            // happens outside the AtmosphereHandler.onStateChange scope.
            req.getSession().setAttribute(ATMOSPHERE, c.hashCode());
        }
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

        if (handler == null && supportSession() && req.getSession(false) != null) {
            handler = ctx.getCometHandler((Integer) req.getSession(false).getAttribute(ATMOSPHERE));
            req.getSession().removeAttribute(ATMOSPHERE);
        }

        if (handler != null && (config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE) == null
                || config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false"))) {
            ctx.resumeCometHandler(handler);
        }
    }

    @Override
    public void action(AtmosphereResourceImpl actionEvent) {
        super.action(actionEvent);
        if (actionEvent.action().type() == Action.TYPE.RESUME && actionEvent.isInScope()) {
            CometContext ctx = CometEngine.getEngine().getCometContext(atmosphereCtx);
            resume(actionEvent.getRequest(), ctx);
        }
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
     * {@link AtmosphereResponse} to an {@link AtmosphereResource}.
     */
    private class VoidCometHandler implements CometHandler {

        private final AtmosphereRequest req;
        private final AtmosphereResponse res;

        private VoidCometHandler(AtmosphereRequest req, AtmosphereResponse res) {
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
                logger.warn("onInterrupt(): encountered exception", ex);
            }
        }
    }
}
