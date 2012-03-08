/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Netty's Framework {@link org.atmosphere.cpr.CometSupport}
 */
public class NettyCometSupport extends AsynchronousProcessor {

    public final static String SUSPEND = NettyCometSupport.class.getName() + ".suspend";
    public final static String RESUME = NettyCometSupport.class.getName() + ".resume";
    public final static String HOOK = NettyCometSupport.class.getName() + ".cometSupportHook";
    public final static String CHANNEL = NettyCometSupport.class.getName() + ".channel";

    private static final Logger logger = LoggerFactory.getLogger(NettyCometSupport.class);

    public NettyCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereFramework.Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        AtmosphereFramework.Action action = null;
        action = suspended(req, res);
        if (action.type == AtmosphereFramework.Action.TYPE.SUSPEND) {
            logger.debug("Suspending response: {}", res);
            req.setAttribute(SUSPEND, action);
            req.setAttribute(HOOK, new CometSupportHook(req,res));
        } else if (action.type == AtmosphereFramework.Action.TYPE.RESUME) {
            req.setAttribute(SUSPEND, action);

            // If resume occurs during a suspend operation, stop processing.
            Boolean resumeOnBroadcast = (Boolean) req.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
            if (resumeOnBroadcast != null && resumeOnBroadcast) {
                return action;
            }
            logger.debug("Resuming response: {}", res);

            AtmosphereFramework.Action nextAction = resumed(req, res);
            if (nextAction.type == AtmosphereFramework.Action.TYPE.SUSPEND) {
                logger.debug("Suspending after resuming response: {}", res);
                req.setAttribute(SUSPEND, action);
            }
        }

        return action;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.isResumed() && r.getRequest().getAttribute(HOOK) != null ) {
            ((CometSupportHook) r.getRequest().getAttribute(HOOK)).resume();
        }
    }

    public final class CometSupportHook {

        private final AtmosphereRequest req;
        private final AtmosphereResponse res;

        public CometSupportHook(AtmosphereRequest req, AtmosphereResponse res) {
            this.req = req;
            this.res = res;
        }

        public void closed(){
            try {
                cancelled(req,res);
            } catch (IOException e) {
                logger.debug("", e);
            } catch (ServletException e) {
                logger.debug("", e);
            }
        }

        public void timedOut() {
            try {
                timedout(req,res);
            } catch (IOException e) {
                logger.debug("", e);
            } catch (ServletException e) {
                logger.debug("", e);
            }
        }

        public void resume() {
            if ( req.getAttribute(CHANNEL) == null ) return;
            try {
                ((AsyncIOWriter) req.getAttribute(CHANNEL) ).close();
            } catch (IOException e) {
                logger.trace("", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportWebSocket() {
        return true;
    }
}
