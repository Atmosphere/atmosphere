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

import org.apache.catalina.CometEvent;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.CometSupport;
import org.jboss.servlet.http.HttpEvent;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * This class gets used when the {@link AtmosphereServlet} fails to autodetect
 * the Servlet Container we are running on.
 * <p/>
 * This {@link CometSupport} implementation uses a blocking approach, meaning
 * the request thread will be blocked until another Thread invoke the
 * {@link Broadcaster#broadcast}
 *
 * @author Jeanfrancois Arcand
 */
public class BlockingIOCometSupport extends AsynchronousProcessor implements CometSupport<AtmosphereResourceImpl> {

    protected final static String LATCH = "org.atmosphere.container.BlockingIOCometSupport.latch";

    protected final ConcurrentHashMap<Integer, CountDownLatch> latchs
            = new ConcurrentHashMap<Integer, CountDownLatch>();

    public BlockingIOCometSupport(AtmosphereConfig config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        Action action = null;
        try {
            action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Suspending" + res);
                }
                suspend(action, req, res);
            } else if (action.type == Action.TYPE.RESUME) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Resuming" + res);
                }

                int latchId = (req.getAttribute(LATCH) == null ? 0 : (Integer)req.getAttribute(LATCH));
                if (req.getSession(true).getAttribute(LATCH) != null) {
                    latchId = (Integer) req.getSession(true).getAttribute(LATCH);
                }
                CountDownLatch latch = latchs.get(latchId);

                if (latch == null && req.getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) == null) {
                    logger.fine("That response " + res + " wasn't suspended.");
                    return action;
                }

                latch.countDown();
                                                                  
                Action nextAction = resumed(req, res);
                if (nextAction.type == Action.TYPE.SUSPEND) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Suspending after Resuming" + res);
                        suspend(action, req, res);
                    }
                }
            }
        } finally {
            CometEvent event = (CometEvent) req.getAttribute(TomcatCometSupport.COMET_EVENT);
            if (event != null)
                event.close();

            HttpEvent he = (HttpEvent) req.getAttribute(JBossWebCometSupport.HTTP_EVENT);
            if (he != null)
                he.close();
        }
        return action;
    }

    /**
     * Suspend the connection by blocking the current {@link Thread}
     *
     * @param action The {@link org.atmosphere.cpr.AtmosphereServlet.Action}
     * @param req    the {@link HttpServletRequest}
     * @param res    the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void suspend(Action action, HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        CountDownLatch latch = new CountDownLatch(1);

        int hash = latch.hashCode();
        req.setAttribute(LATCH, hash);
        latchs.put(hash, latch);

        if (supportSession()) {
            // Store as well in the session in case the resume operation
            // happens outside the AtmosphereHandler.onStateChange scope.
            req.getSession().setAttribute(String.valueOf(req.hashCode()), hash);
        }

        try {
            if (action.timeout != -1) {
                latch.await(action.timeout, TimeUnit.MILLISECONDS);
            } else {
                latch.await();
            }
        } catch (InterruptedException ex) {
            logger.log(Level.FINEST, "", ex);
        } finally {
            latchs.remove(hash);
            timedout(req, res);
        }
    }

    @Override
    public Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        int latchId = -1;

        if (req.getAttribute(LATCH) != null) {
            latchId = (Integer) req.getAttribute(LATCH);
        }
        CountDownLatch latch = latchs.remove(latchId);
        Action a = super.cancelled(req,res);
        latch.countDown();
        return a;        
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action(AtmosphereResourceImpl actionEvent) {
        try {
            super.action(actionEvent);            
            if (actionEvent.action().type == Action.TYPE.RESUME && actionEvent.isInScope()) {
                int latchId = -1;
                HttpServletRequest req = actionEvent.getRequest();

                if (req.getAttribute(LATCH) != null) {
                    latchId = (Integer) req.getAttribute(LATCH);
                }

                if (latchId == -1 && supportSession()) {
                    if (req.getSession().getAttribute(LATCH) != null) {
                        latchId = (Integer) req.getSession().getAttribute(LATCH);
                    }
                }

                String s = config.getInitParameter(AtmosphereServlet.RESUME_AND_KEEPALIVE);
                if (latchId != -1 && (s == null || s.equalsIgnoreCase("false"))) {
                    CountDownLatch latch = latchs.remove(latchId);
                    latch.countDown();
                } else if (req.getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) == null) {
                    logger.log(Level.SEVERE, "Unable to resume the suspended connection");
                }
            }
        } catch (Exception ex) {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "", ex);
            }
        }
    }
}
