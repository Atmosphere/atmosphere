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
package org.atmosphere.cpr;

import org.atmosphere.util.Utils;
import org.atmosphere.util.uri.UriTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

import static org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * Base class which implement the semantics of suspending and resuming of a
 * Comet/WebSocket Request.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class AsynchronousProcessor implements AsyncSupport<AtmosphereResourceImpl> {

    private static final Logger logger = LoggerFactory.getLogger(AsynchronousProcessor.class);
    protected static final Action timedoutAction = new Action(Action.TYPE.TIMEOUT);
    protected static final Action cancelledAction = new Action(Action.TYPE.CANCELLED);
    protected final AtmosphereConfig config;
    protected final ConcurrentHashMap<AtmosphereRequest, AtmosphereResource>
            aliveRequests = new ConcurrentHashMap<AtmosphereRequest, AtmosphereResource>();
    private boolean trackActiveRequest = false;
    private final ScheduledExecutorService closedDetector = Executors.newScheduledThreadPool(1);

    public AsynchronousProcessor(AtmosphereConfig config) {
        this.config = config;
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {

        String maxInactive = sc.getInitParameter(MAX_INACTIVE) != null ? sc.getInitParameter(MAX_INACTIVE) :
                config.getInitParameter(MAX_INACTIVE);

        if (maxInactive != null) {
            trackActiveRequest = true;
            final long maxInactiveTime = Long.parseLong(maxInactive);
            if (maxInactiveTime <= 0) return;

            closedDetector.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    for (AtmosphereRequest req : aliveRequests.keySet()) {
                        long l = (Long) req.getAttribute(MAX_INACTIVE);
                        if (l > 0 && System.currentTimeMillis() - l > maxInactiveTime) {
                            try {
                                logger.debug("Close detector disconnecting {}. Current size {}", req, aliveRequests.size());
                                AtmosphereResourceImpl r = (AtmosphereResourceImpl) aliveRequests.remove(req);
                                cancelled(req, r.getResponse(false));
                            } catch (Throwable e) {
                                logger.warn("closedDetector", e);
                            } finally {
                                try {
                                    req.setAttribute(MAX_INACTIVE, (long) -1);
                                } catch (Throwable t) {
                                    logger.trace("closedDetector", t);
                                }
                            }
                        }
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }


    /**
     * Is {@link HttpSession} supported
     *
     * @return true if supported
     */
    protected boolean supportSession() {
        return config.isSupportSession();
    }

    /**
     * Return the container's name.
     */
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo();
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the suspended
     * method when the first request comes in. The returned value, of type
     * {@link Action}, tells the proprietary Comet {@link Servlet}
     * to suspended or not the current {@link AtmosphereResponse}.
     *
     * @param request  the {@link AtmosphereRequest}
     * @param response the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action suspended(AtmosphereRequest request, AtmosphereResponse response) throws IOException, ServletException {
        return action(request, response);
    }

    /**
     * Invoke the {@link AtmosphereHandler#onRequest} method.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    Action action(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        if (Utils.webSocketEnabled(req) && !supportWebSocket()) {
            res.setStatus(501);
            res.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
            res.flushBuffer();
            return new Action();
        }

        if (config.handlers().isEmpty()) {
            logger.error("No AtmosphereHandler found. Make sure you define it inside web/atmosphere.xml or annotate using @AtmosphereHandlerService");
            throw new AtmosphereMappingException("No AtmosphereHandler found. Make sure you define it inside web/atmosphere.xml or annotate using @AtmosphereHandlerService");
        }

        if (res.request() == null) {
            res.request(req);
        }

        if (supportSession()) {
            // Create the session needed to support the Resume
            // operation from disparate requests.
            HttpSession session = req.getSession(true);
            // Do not allow times out.
            SessionTimeoutSupport.setupTimeout(session);
        }

        req.setAttribute(FrameworkConfig.SUPPORT_SESSION, supportSession());

        AtmosphereHandlerWrapper handlerWrapper = map(req);
        // Check Broadcaster state. If destroyed, replace it.
        Broadcaster b = handlerWrapper.broadcaster;
        if (b.isDestroyed()) {
            synchronized (handlerWrapper) {
                config.getBroadcasterFactory().remove(b, b.getID());
                handlerWrapper.broadcaster = config.getBroadcasterFactory().get(b.getID());
            }
        }

        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE);
        if (resource == null) {
            // TODO: cast is dangerous
            resource = (AtmosphereResourceImpl)
                    AtmosphereResourceFactory.create(config, handlerWrapper.broadcaster, res, this, handlerWrapper.atmosphereHandler);
        } else {
            resource.setBroadcaster(handlerWrapper.broadcaster).atmosphereHandler(handlerWrapper.atmosphereHandler);
        }

        req.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);
        req.setAttribute(FrameworkConfig.ATMOSPHERE_HANDLER, handlerWrapper.atmosphereHandler);

        // Globally defined
        Action a = invokeInterceptors(config.framework().interceptors(), resource);
        if (a == null || a.type() != Action.TYPE.CONTINUE) {
            return a;
        }

        // Per AtmosphereHandler
        a = invokeInterceptors(handlerWrapper.interceptors, resource);
        if (a == null || a.type() != Action.TYPE.CONTINUE) {
            return a;
        }

        try {
            handlerWrapper.atmosphereHandler.onRequest(resource);
        } catch (IOException t) {
            resource.onThrowable(t);
            throw t;
        }

        postInterceptors(handlerWrapper.interceptors, resource);
        postInterceptors(config.framework().interceptors(), resource);

        if (trackActiveRequest && resource.getAtmosphereResourceEvent().isSuspended() && req.getAttribute(FrameworkConfig.CANCEL_SUSPEND_OPERATION) == null) {
            req.setAttribute(MAX_INACTIVE, System.currentTimeMillis());
            aliveRequests.put(req, resource);
        }
        return resource.action();
    }

    private Action invokeInterceptors(List<AtmosphereInterceptor> c, AtmosphereResource r) {
        Action a = Action.CONTINUE;
        for (AtmosphereInterceptor arc : c) {
            a = arc.inspect(r);
            if (a == null || a.type() != Action.TYPE.CONTINUE) {
                logger.trace("Interceptor {} interrupted the dispatch with {}", arc, a);
                return a;
            }
        }
        return a;
    }

    private void postInterceptors(List<AtmosphereInterceptor> c, AtmosphereResource r) {
        for (int i = c.size() - 1; i > -1; i--) {
            c.get(i).postInspect(r);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void action(AtmosphereResourceImpl r) {
        if (trackActiveRequest) {
            aliveRequests.remove(r.getRequest(false));
        }
    }

    protected AtmosphereHandlerWrapper map(String path) {
        AtmosphereHandlerWrapper atmosphereHandlerWrapper = config.handlers().get(path);

        if (atmosphereHandlerWrapper == null) {
            final Map<String, String> m = new HashMap<String, String>();
            for (Map.Entry<String, AtmosphereHandlerWrapper> e : config.handlers().entrySet()) {
                UriTemplate t = new UriTemplate(e.getKey());
                logger.trace("Trying to map {} to {}", t, path);
                if (t.match(path, m)) {
                    atmosphereHandlerWrapper = e.getValue();
                    logger.trace("Mapped {} to {}", t, e.getValue().atmosphereHandler);
                    break;
                }
            }
        }
        return atmosphereHandlerWrapper;
    }

    /**
     * Return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     *
     * @param req the {@link AtmosphereResponse}
     * @return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     * @throws javax.servlet.ServletException
     */
    protected AtmosphereHandlerWrapper map(AtmosphereRequest req) throws ServletException {
        String path;
        if (req.getPathInfo() != null) {
            path = req.getServletPath() + req.getPathInfo();
        } else {
            path = req.getServletPath();
        }

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        AtmosphereHandlerWrapper atmosphereHandlerWrapper = map(path + (path.endsWith("/") ? "all" : "/all"));
        if (atmosphereHandlerWrapper == null) {
            // (2) First, try exact match
            atmosphereHandlerWrapper = map(path);

            if (atmosphereHandlerWrapper == null) {
                // (3) Wildcard
                atmosphereHandlerWrapper = map(path + "*");

                // (4) try without a path
                if (atmosphereHandlerWrapper == null) {
                    String p = path.lastIndexOf("/") == 0 ? "/" : path.substring(0, path.lastIndexOf("/"));
                    while (p.length() > 0) {
                        atmosphereHandlerWrapper = map(p);

                        // (3.1) Try path wildcard
                        if (atmosphereHandlerWrapper != null) {
                            break;
                        }
                        p = p.substring(0, p.lastIndexOf("/"));
                    }
                }
            }
        }

        if (atmosphereHandlerWrapper == null) {
            logger.debug("No AtmosphereHandler maps request for {} with mapping {}", path, config.handlers());
            throw new AtmosphereMappingException("No AtmosphereHandler maps request for " + path);
        }
        config.getBroadcasterFactory().add(atmosphereHandlerWrapper.broadcaster,
                atmosphereHandlerWrapper.broadcaster.getID());
        return atmosphereHandlerWrapper;
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the resume
     * method when the Atmosphere's application decide to resume the {@link AtmosphereResponse}.
     * The returned value, of type
     * {@link Action}, tells the proprietary Comet {@link Servlet}
     * to resume (again), suspended or do nothing with the current {@link AtmosphereResponse}.
     *
     * @param request  the {@link AtmosphereRequest}
     * @param response the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action resumed(AtmosphereRequest request, AtmosphereResponse response)
            throws IOException, ServletException {
        SessionTimeoutSupport.restoreTimeout(request);
        return action(request, response);
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the timedout
     * method when the underlying WebServer time out the {@link AtmosphereResponse}.
     * The returned value, of type
     * {@link Action}, tells the proprietary Comet {@link Servlet}
     * to resume (again), suspended or do nothing with the current {@link AtmosphereResponse}.
     *
     * @param request  the {@link AtmosphereRequest}
     * @param response the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action timedout(AtmosphereRequest request, AtmosphereResponse response)
            throws IOException, ServletException {

        AtmosphereResourceImpl r = null;

        try {
            SessionTimeoutSupport.restoreTimeout(request);

            if (trackActiveRequest) {
                long l = (Long) request.getAttribute(MAX_INACTIVE);
                if (l == -1) {
                    // The closedDetector closed the connection.
                    return timedoutAction;
                }
                request.setAttribute(MAX_INACTIVE, (long) -1);
            }

            logger.debug("Timing out the connection for request {}", request);

            // Something went wrong.
            if (request == null || response == null) {
                logger.warn("Invalid Request/Response: {}/{}", request, response);
                return timedoutAction;
            }

            r = (AtmosphereResourceImpl) request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

            if (r != null && r.getAtmosphereResourceEvent().isSuspended()) {
                r.getAtmosphereResourceEvent().setIsResumedOnTimeout(true);

                Broadcaster b = r.getBroadcaster();
                if (b instanceof DefaultBroadcaster) {
                    ((DefaultBroadcaster) b).broadcastOnResume(r);
                }

                if (request.getAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT) != null) {
                    r.getAtmosphereResourceEvent().setIsResumedOnTimeout(
                            (Boolean) request.getAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT));
                }

                invokeAtmosphereHandler(r);
            }
        } catch (Throwable t) {
            logger.error("failed to timeout resource {}", r, t);
        } finally {
            config.framework().notify(Action.TYPE.TIMEOUT, request, response);
            try {
                if (r != null) {
                    r.notifyListeners();
                    r.setIsInScope(false);
                    r.cancel();
                }
            } catch (Throwable t) {
                logger.trace("timedout", t);
            } finally {

                try {
                    response.getOutputStream().close();
                } catch (Throwable t) {
                    try {
                        response.getWriter().close();
                    } catch (Throwable t2) {
                    }
                }

                if (r != null) {
                    destroyResource(r);
                }
            }
        }

        return timedoutAction;
    }

    void invokeAtmosphereHandler(AtmosphereResourceImpl r) throws IOException {
        if (!r.isInScope()) return;

        AtmosphereRequest req = r.getRequest(false);
        String disableOnEvent = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.DISABLE_ONSTATE_EVENT);
        r.getAtmosphereResourceEvent().setMessage(r.writeOnTimeout());
        try {
            if (disableOnEvent == null || !disableOnEvent.equals(String.valueOf(true))) {
                AtmosphereHandler atmosphereHandler =
                        (AtmosphereHandler)
                                req.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);

                synchronized (r) {
                    atmosphereHandler.onStateChange(r.getAtmosphereResourceEvent());

                    Meteor m = (Meteor) req.getAttribute(AtmosphereResourceImpl.METEOR);
                    if (m != null) {
                        m.destroy();
                    }
                }
                req.removeAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
            }
        } catch (IOException ex) {
            try {
                r.onThrowable(ex);
            } catch (Throwable t) {
                logger.warn("failed calling onThrowable()", ex);
            }
        }
    }

    public static void destroyResource(AtmosphereResource r) {
        if (r == null) return;

        try {
            r.removeEventListeners();
            try {
                AtmosphereResourceImpl.class.cast(r).getBroadcaster(false).removeAtmosphereResource(r);
            } catch (IllegalStateException ex) {
                logger.trace(ex.getMessage(), ex);
            }
            if (BroadcasterFactory.getDefault() != null) {
                BroadcasterFactory.getDefault().removeAllAtmosphereResource(r);
            }
        } catch (Throwable t) {
            logger.trace("destroyResource", t);
        }
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the cancelled
     * method when the underlying WebServer detect that the client closed
     * the connection.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        synchronized (req) {
            SessionTimeoutSupport.restoreTimeout(req);

            AtmosphereResourceImpl r = null;
            try {
                if (trackActiveRequest) {
                    long l = (Long) req.getAttribute(MAX_INACTIVE);
                    if (l == -1) {
                        // The closedDetector closed the connection.
                        return timedoutAction;
                    }
                    req.setAttribute(MAX_INACTIVE, (long) -1);
                }

                logger.debug("Cancelling the connection for request {}", req);

                r = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                if (r != null) {
                    r.getAtmosphereResourceEvent().setCancelled(true);
                    invokeAtmosphereHandler(r);

                    try {
                        r.getResponse().getOutputStream().close();
                    } catch (Throwable t) {
                        try {
                            r.getResponse().getWriter().close();
                        } catch (Throwable t2) {
                        }
                    }
                }
            } catch (Throwable ex) {
                // Something wrong happenned, ignore the exception
                logger.debug("failed to cancel resource: " + r, ex);
            } finally {
                config.framework().notify(Action.TYPE.CANCELLED, req, res);
                try {
                    if (r != null) {
                        r.notifyListeners();
                        r.setIsInScope(false);
                        r.cancel();
                    }
                } catch (Throwable t) {
                    logger.trace("cancel", t);
                } finally {
                    if (r != null) {
                        destroyResource(r);
                    }
                }
            }
        }

        return cancelledAction;
    }

    protected void shutdown() {
        closedDetector.shutdownNow();
        for (AtmosphereResource resource : aliveRequests.values()) {
            try {
                resource.resume();
            } catch (Throwable t) {
                // Something wrong happenned, ignore the exception
                logger.debug("failed on resume: " + resource, t);
            }
        }
    }

    public boolean supportWebSocket() {
        return false;
    }

    /**
     * An Callback class that can be used by Framework integrator to handle the close/timedout/resume life cycle
     * of an {@link AtmosphereResource}. This class support only support {@link AsyncSupport} implementation that
     * extends {@link AsynchronousProcessor}
     */
    public final static class AsynchronousProcessorHook {

        private final AtmosphereResourceImpl r;

        public AsynchronousProcessorHook(AtmosphereResourceImpl r) {
            this.r = r;
            if (!AsynchronousProcessor.class.isAssignableFrom(r.asyncSupport.getClass())) {
                throw new IllegalStateException("AsyncSupport must extends AsynchronousProcessor");
            }
        }

        public void closed() {
            try {
                ((AsynchronousProcessor) r.asyncSupport).cancelled(r.getRequest(false), r.getResponse(false));
            } catch (IOException e) {
                logger.debug("", e);
            } catch (ServletException e) {
                logger.debug("", e);
            }
        }

        public void timedOut() {
            try {
                ((AsynchronousProcessor) r.asyncSupport).timedout(r.getRequest(false), r.getResponse(false));
            } catch (IOException e) {
                logger.debug("", e);
            } catch (ServletException e) {
                logger.debug("", e);
            }
        }

        public void resume() {
            ((AsynchronousProcessor) r.asyncSupport).action(r);
        }
    }
}
