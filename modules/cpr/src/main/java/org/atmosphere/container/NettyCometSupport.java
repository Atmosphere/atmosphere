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

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Netty's Framework {@link org.atmosphere.cpr.CometSupport}
 */
public class NettyCometSupport extends AsynchronousProcessor {

    public final static String SUSPEND = NettyCometSupport.class.getName() + ".suspend";
    public final static String RESUME = NettyCometSupport.class.getName() + ".resume";

    private static final Logger logger = LoggerFactory.getLogger(BlockingIOCometSupport.class);

    public NettyCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    public AtmosphereServlet.Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        AtmosphereServlet.Action action = null;
        action = suspended(req, res);
        if (action.type == AtmosphereServlet.Action.TYPE.SUSPEND) {
            logger.debug("Suspending response: {}", res);
            req.setAttribute(SUSPEND, new Boolean(true));
        } else if (action.type == AtmosphereServlet.Action.TYPE.RESUME) {
            logger.debug("Resuming response: {}", res);

            AtmosphereServlet.Action nextAction = resumed(req, res);
            if (nextAction.type == AtmosphereServlet.Action.TYPE.SUSPEND) {
                logger.debug("Suspending after resuming response: {}", res);
                req.setAttribute(SUSPEND, new Boolean(true));
            }
        }

        return action;
    }
}
