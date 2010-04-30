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
import org.atmosphere.cpr.CometSupport;
import org.mortbay.util.ajax.Continuation;
import org.mortbay.util.ajax.ContinuationSupport;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Comet Portable Runtime implementation on top of Jetty's Continuation.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyCometSupport extends AsynchronousProcessor implements CometSupport<AtmosphereResourceImpl> {

    private final ConcurrentLinkedQueue<Continuation> resumed = new ConcurrentLinkedQueue<Continuation>();

    public JettyCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        Continuation c = ContinuationSupport.getContinuation(req, null);
        Action action = null;

        if (!c.isResumed() && !c.isPending()) {
            // This will throw an exception
            action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Suspending " + res);
                }

                // Do nothing except setting the times out
                if (action.timeout != -1) {
                    c.suspend(action.timeout);
                } else {
                    c.suspend(0);
                }
            }
        } else {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Resuming " + res);
            }

            if (!resumed.remove(c)) {
                c.reset();

                if (req.getAttribute(AtmosphereServlet.RESUMED_ON_TIMEOUT) == null) {
                    timedout(req, res);
                } else {
                    resumed(req, res);
                }
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
        if (r.action().type == Action.TYPE.RESUME) {
            Continuation c = ContinuationSupport.getContinuation(r.getRequest(), null);
            resumed.offer(c);
            if (config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE) == null
                    || config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE).equalsIgnoreCase("false")) {
                c.resume();
            } else {
                try {
                    r.getResponse().flushBuffer();
                } catch (IOException e) {
                }
            }
        }
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        Continuation c = ContinuationSupport.getContinuation(req, null);
        if (c != null) c.reset();
        return super.cancelled(req,res);
    }
}
