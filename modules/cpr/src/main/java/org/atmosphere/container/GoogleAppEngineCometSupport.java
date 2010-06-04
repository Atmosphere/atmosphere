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

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.util.LoggerUtils;
import org.atmosphere.util.gae.GAEDefaultBroadcaster;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * This class gets used when the {@link AtmosphereServlet} detected the
 * Google App Engine support. In that case, the {@link GAEDefaultBroadcaster} wil be used.
 *
 * @author Jeanfrancois Arcand
 */
public class GoogleAppEngineCometSupport extends BlockingIOCometSupport {

    public GoogleAppEngineCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * Suspend the connection by blocking the current {@link Thread} no more than 30 seconds
     *
     * @param action The {@link AtmosphereServlet.Action}
     * @param req    the {@link HttpServletRequest}
     * @param res    the {@link HttpServletResponse}
     */
    @Override
    protected void suspend(Action action, HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        CountDownLatch latch = new CountDownLatch(1);

        req.setAttribute(LATCH, latch.hashCode());
        latchs.put(latch.hashCode(), latch);

        if (supportSession()) {
            // Store as well in the session in case the resume operation
            // happens outside the AtmosphereHandler.onStateChange scope.
            req.getSession().setAttribute(LATCH, latch.hashCode());
        }
        try {
            // Google App Engine doesn't allow a thread to block more than 30 seconds
            if (action.timeout != -1 && action.timeout < 30000) {
                latch.await(action.timeout, TimeUnit.MILLISECONDS);
            } else {
                latch.await(30000, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ex) {
            LoggerUtils.getLogger().log(Level.SEVERE, "Unable to resume the suspended connection");
        } finally {
            timedout(req, res);
        }
    }
}
