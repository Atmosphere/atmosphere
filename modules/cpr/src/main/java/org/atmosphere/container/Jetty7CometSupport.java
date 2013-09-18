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
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import java.io.IOException;

/**
 * Comet Portable Runtime implementation on top of Jetty's Continuation.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty7CometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(Jetty7CometSupport.class);

    public Jetty7CometSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(final AtmosphereRequest req, final AtmosphereResponse res) throws IOException, ServletException {
        Action action = null;

        Continuation c = (Continuation) req.getAttribute(Continuation.class.getName());

        if (c == null || c.isInitial()) {
            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND && req.getAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION) == null) {
                c = getContinuation(req);
                req.setAttribute(Continuation.class.getName(), c);

                if (action.timeout() != -1) {
                    c.setTimeout(action.timeout());
                } else {
                    // Jetty 7 does something really weird if you set it to
                    // Long.MAX_VALUE, which is to resume automatically.
                    c.setTimeout(Integer.MAX_VALUE);
                }

                c.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE));
                c.addContinuationListener(new ContinuationListener() {

                    @Override
                    public void onComplete(Continuation continuation) {
                        AtmosphereResourceImpl r = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                        if (r != null) {
                            try {
                                r.cancel();
                            } catch (IOException e) {
                            }
                        }
                    }

                    @Override
                    public void onTimeout(Continuation continuation) {
                        AtmosphereResourceImpl r = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                        if (r != null) {
                            try {
                                timedout(r.getRequest(), r.getResponse());
                            } catch (Throwable t) {
                                logger.error("", t);
                            }
                        } else {
                            logger.trace("AtmosphereResource was null");
                        }
                        try {
                            continuation.complete();
                        } catch (Throwable t) {
                        }
                    }
                });

                if (req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE) != null) {
                    c.suspend(res);
                }
            } else if (action.type() == Action.TYPE.RESUME) {
                // If resume occurs during a suspend operation, stop processing.
                Boolean resumeOnBroadcast = (Boolean) req.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
                if (resumeOnBroadcast != null && resumeOnBroadcast) {
                    return action;
                }

                c = (Continuation) req.getAttribute(Continuation.class.getName());
                if (c != null) {
                    if (c.isSuspended()) {
                        try {
                            c.complete();
                        } catch (IllegalStateException ex) {
                            logger.trace("Continuation.complete()", ex);
                        } finally {
                            resumed(req, res);
                        }
                    }
                }
            }
        }
        return action;
    }

    protected Continuation getContinuation(AtmosphereRequest req) {
        return ContinuationSupport.getContinuation(req);
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        super.action(r);

        ServletRequest request = r.getRequest(false);
        while (request != null) {
            Continuation c = (Continuation) request.getAttribute(Continuation.class.getName());
            if (c != null) {
                try {
                    if (c.isSuspended()) {
                        c.complete();
                    }
                } catch (IllegalStateException ex) {
                    logger.trace("c.complete()", ex);
                } finally {
                    r.getRequest(false).setAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION, true);
                }
                request.removeAttribute(Continuation.class.getName());
                return;
            } else {
                return;
            }
        }
    }
}
