/*
 * Copyright 2014 Jeanfrancois Arcand
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
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

import static org.atmosphere.cpr.FrameworkConfig.ASYNCHRONOUS_HOOK;

/**
 * Netty's Framework {@link org.atmosphere.cpr.AsyncSupport} and framework running on top of it, like vert.x and Play!
 */
public class NettyCometSupport extends AsynchronousProcessor {

    public final static String SUSPEND = NettyCometSupport.class.getName() + ".suspend";
    public final static String RESUME = NettyCometSupport.class.getName() + ".resume";

    private static final Logger logger = LoggerFactory.getLogger(NettyCometSupport.class);

    public NettyCometSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action;
        action = suspended(req, res);
        if (action.type() == Action.TYPE.SUSPEND) {
            req.setAttribute(SUSPEND, action);
        } else if (action.type() == Action.TYPE.RESUME) {
            req.setAttribute(SUSPEND, action);

            // If resume occurs during a suspend operation, stop processing.
            Boolean resumeOnBroadcast = (Boolean) req.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
            if (resumeOnBroadcast != null && resumeOnBroadcast) {
                return action;
            }

            Action nextAction = resumed(req, res);
            if (nextAction.type() == Action.TYPE.SUSPEND) {
                req.setAttribute(SUSPEND, action);
            }
        }

        return action;
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);
        if (r.isResumed() && r.getRequest(false).getAttribute(ASYNCHRONOUS_HOOK) != null) {
            complete(r);
        }
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public AsyncSupport complete(AtmosphereResourceImpl r) {
        try {
            AtmosphereResponse response = r.getResponse(false);
            AsyncIOWriter a = response.getAsyncIOWriter();
            if (a != null) {
                a.close(response);
            }
        } catch (IOException e) {
            logger.trace("", e);
        }
        return this;
    }
}
