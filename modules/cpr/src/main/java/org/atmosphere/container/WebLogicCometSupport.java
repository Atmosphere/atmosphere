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

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weblogic.servlet.http.AbstractAsyncServlet;
import weblogic.servlet.http.RequestResponseKey;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Weblogic support.
 *
 * @author Jeanfrancois Arcand
 */
public class WebLogicCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WebLogicCometSupport.class);

    public static final String RRK = "RequestResponseKey";

    public WebLogicCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        Action action = suspended(req, res);
        if (action.type == Action.TYPE.SUSPEND) {
            logger.debug("Suspending response: {}", res);
        } else if (action.type == Action.TYPE.RESUME) {
            logger.debug("Resuming response: {}", res);

            Action nextAction = resumed(req, res);
            if (nextAction.type == Action.TYPE.SUSPEND) {
                logger.debug("Suspending after resuming response: {}", res);
            }
        }
        return action;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl actionEvent) {
        super.action(actionEvent);
        if (actionEvent.isInScope() && actionEvent.action().type == Action.TYPE.RESUME &&
                (config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE) == null
                        || config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false"))) {
            try {
                RequestResponseKey rrk = (RequestResponseKey) actionEvent.getRequest().getSession().getAttribute(RRK);
                AbstractAsyncServlet.notify(rrk, null);
            } catch (IOException ex) {
                logger.debug("action failed", ex);
            }
        }
    }

}
