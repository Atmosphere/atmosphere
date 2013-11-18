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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * This class is used when the {@link org.atmosphere.cpr.AtmosphereFramework} fails to autodetect
 * the Servlet Container we are running on.
 * <p/>
 * This {@link org.atmosphere.cpr.AsyncSupport} implementation uses a blocking approach, meaning
 * the request thread will be blocked until another Thread invoke the {@link Broadcaster#broadcast}.
 *
 * @author Jeanfrancois Arcand
 */
public class BlockingIOCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(BlockingIOCometSupport.class);

    protected static final String LATCH = BlockingIOCometSupport.class.getName() + ".latch";

    public BlockingIOCometSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = null;
        try {
            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
                suspend(action, req, res);
            } else if (action.type() == Action.TYPE.RESUME) {
                CountDownLatch latch = (CountDownLatch) req.getAttribute(LATCH);

                if (latch == null || req.getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) == null) {
                    logger.debug("response wasn't suspended: {}", res);
                    return action;
                }

                latch.countDown();

                Action nextAction = resumed(req, res);
                if (nextAction.type() == Action.TYPE.SUSPEND) {
                    suspend(action, req, res);
                }
            }
        } finally {
            Object event = req.getAttribute(TomcatCometSupport.COMET_EVENT);
            if (event != null) {
                try {
                    Class.forName(org.apache.catalina.CometEvent.class.getName());

                    if (org.apache.catalina.CometEvent.class.isAssignableFrom(event.getClass())) {
                        org.apache.catalina.CometEvent.class.cast(event).close();
                    }
                } catch (Throwable e) {
                    logger.trace("", e);
                }


                try {
                    Class.forName(org.apache.catalina.comet.CometEvent.class.getName());

                    if (org.apache.catalina.comet.CometEvent.class.isAssignableFrom(event.getClass())) {
                        org.apache.catalina.comet.CometEvent.class.cast(event).close();
                    }
                } catch (Throwable e) {
                    logger.trace("", e);
                }
            }

            try {
                event = req.getAttribute(JBossWebCometSupport.HTTP_EVENT);
                if (event != null) {
                    Class.forName(org.jboss.servlet.http.HttpEvent.class.getName());
                    org.jboss.servlet.http.HttpEvent.class.cast(event).close();
                }
            } catch (Throwable e) {
                logger.trace("", e);
            }
        }
        return action;
    }

    /**
     * Suspend the connection by blocking the current {@link Thread}
     *
     * @param action The {@link Action}
     * @param req    the {@link AtmosphereRequest}
     * @param res    the {@link AtmosphereResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void suspend(Action action, AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        final CountDownLatch latch = new CountDownLatch(1);
        req.setAttribute(LATCH, latch);

        boolean ok = true;
        try {
            AtmosphereResource resource = req.resource();
            resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onResume(AtmosphereResourceEvent event) {
                    latch.countDown();
                }
            });
            if (action.timeout() != -1) {
                ok = latch.await(action.timeout(), TimeUnit.MILLISECONDS);
            } else {
                latch.await();
            }
        } catch (InterruptedException ex) {
            logger.trace("", ex);
        } finally {
            if (!ok) {
                timedout(req, res);
            } else {
                if (req.resource() != null) {
                    AtmosphereResourceImpl.class.cast(req.resource()).cancel();
                }
            }
        }
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action a = super.cancelled(req, res);
        if (req.getAttribute(LATCH) != null) {
            CountDownLatch latch = (CountDownLatch) req.getAttribute(LATCH);
            latch.countDown();
        }
        return a;
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        try {
            super.action(r);
            if (r.action().type() == Action.TYPE.RESUME) {
                AtmosphereRequest req = r.getRequest(false);
                CountDownLatch latch = null;

                if (req.getAttribute(LATCH) != null) {
                    latch = (CountDownLatch) req.getAttribute(LATCH);
                }

                String s = config.getInitParameter(ApplicationConfig.RESUME_AND_KEEPALIVE);
                if (latch != null && (s == null || s.equalsIgnoreCase("false"))) {
                    latch.countDown();
                } else if (req.getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) == null) {
                    logger.trace("Unable to resume the suspended connection");
                }
            }
        } catch (Exception ex) {
            logger.error("", ex);
        }
    }
}
