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

import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper;
import org.atmosphere.util.uri.UriTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * Base class which implement the semantics of suspending and resuming of a
 * Comet Request.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class AsynchronousProcessor implements CometSupport<AtmosphereResourceImpl> {

    private static final Logger logger = LoggerFactory.getLogger(AsynchronousProcessor.class);

    protected static final Action timedoutAction = new Action(Action.TYPE.TIMEOUT);
    protected static final Action cancelledAction = new Action(Action.TYPE.CANCELLED);
    private static final int DEFAULT_SESSION_TIMEOUT = 1800;

    protected final AtmosphereConfig config;

    protected final ConcurrentHashMap<HttpServletRequest, AtmosphereResource<HttpServletRequest, HttpServletResponse>>
            aliveRequests = new ConcurrentHashMap<HttpServletRequest, AtmosphereResource<HttpServletRequest, HttpServletResponse>>();

    private final ScheduledExecutorService closedDetector = Executors.newScheduledThreadPool(1);

    public AsynchronousProcessor(AtmosphereConfig config) {
        this.config = config;
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {

        String maxInactive = sc.getInitParameter(MAX_INACTIVE) != null ? sc.getInitParameter(MAX_INACTIVE) :
                config.getInitParameter(MAX_INACTIVE);
        if (maxInactive != null) {
            final long maxInactiveTime = Long.parseLong(maxInactive);
            if (maxInactiveTime <= 0) return;

            closedDetector.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    for (HttpServletRequest req : aliveRequests.keySet()) {
                        long l = (Long) req.getAttribute(MAX_INACTIVE);
                        if (l > 0 && System.currentTimeMillis() - l > maxInactiveTime) {
                            try {
                                cancelled(req, aliveRequests.get(req).getResponse());
                                req.setAttribute(MAX_INACTIVE, (long) -1);
                            } catch (IOException e) {
                                logger.trace("closedDetector", e);
                            } catch (ServletException e) {
                                logger.trace("closedDetector", e);
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
     * {@link AtmosphereServlet.Action}, tells the proprietary Comet {@link Servlet}
     * to suspended or not the current {@link HttpServletResponse}.
     *
     * @param request  the {@link HttpServletRequest}
     * @param response the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action suspended(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        return action(request, response);
    }

    /**
     * Invoke the {@link AtmosphereHandler#onRequest} method.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    Action action(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        boolean webSocketEnabled = false;
        if (req.getHeaders("Connection") != null && req.getHeaders("Connection").hasMoreElements()) {
            String[] e = req.getHeaders("Connection").nextElement().split(",");
            for (String upgrade : e) {
                if (upgrade.equalsIgnoreCase("Upgrade")) {
                    webSocketEnabled = true;
                    break;
                }
            }
        }

        if (webSocketEnabled && !supportWebSocket()) {
            res.setStatus(501);
            res.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
            res.flushBuffer();
            return new Action();
        }

        if (config.handlers().isEmpty()) {
            logger.error("No AtmosphereHandler found. Make sure you define it inside META-INF/atmosphere.xml");
            throw new ServletException("No AtmosphereHandler found. Make sure you define it inside META-INF/atmosphere.xml");
        }

        if (supportSession()) {
            // Create the session needed to support the Resume
            // operation from disparate requests.
            HttpSession session = req.getSession(true);
            // Do not allow times out.
            if (session.getMaxInactiveInterval() == DEFAULT_SESSION_TIMEOUT) {
                session.setMaxInactiveInterval(-1);
            }
        }

        req.setAttribute(FrameworkConfig.SUPPORT_SESSION, supportSession());

        AtmosphereHandlerWrapper handlerWrapper = map(req);
        AtmosphereResourceImpl resource = new AtmosphereResourceImpl(config, handlerWrapper.broadcaster, req, res, this, handlerWrapper.atmosphereHandler);

        req.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);
        req.setAttribute(FrameworkConfig.ATMOSPHERE_HANDLER, handlerWrapper.atmosphereHandler);

        try {
            handlerWrapper.atmosphereHandler.onRequest(resource);
        } catch (IOException t) {
            resource.onThrowable(t);
            throw t;
        }

        if (resource.getAtmosphereResourceEvent().isSuspended()) {
            req.setAttribute(MAX_INACTIVE, System.currentTimeMillis());
            aliveRequests.put(req, resource);
        }
        return resource.action();
    }

    /**
     * {@inheritDoc}
     */
    public void action(AtmosphereResourceImpl r) {
        aliveRequests.remove(r.getRequest());
    }

    /**
     * Return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     *
     * @param req the {@link HttpServletResponse}
     * @return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     * @throws javax.servlet.ServletException
     */
    protected AtmosphereHandlerWrapper map(HttpServletRequest req) throws ServletException {
        String path = req.getRequestURI();
        if (path == null || path.length() == 0) {
            path = "/*";
        }

        AtmosphereHandlerWrapper atmosphereHandlerWrapper = config.handlers().get(path);
        if (atmosphereHandlerWrapper == null) {
            final Map<String, String> m = new HashMap<String, String>();
            for (Map.Entry<String,AtmosphereHandlerWrapper> e : config.handlers().entrySet()) {
                UriTemplate t = new UriTemplate(e.getKey());
                logger.trace("Trying to map {} to {}", t, path);
                if (t.match(path, m)) {
                    atmosphereHandlerWrapper = e.getValue();
                    logger.trace("Mapped {} to {}", t, e.getValue());
                    break;
                }
            }
        }

        if (atmosphereHandlerWrapper == null){
            throw new ServletException("No AtmosphereHandler maps request for " + path);
        }
        config.getBroadcasterFactory().add(atmosphereHandlerWrapper.broadcaster,
                atmosphereHandlerWrapper.broadcaster.getID());
        return atmosphereHandlerWrapper;
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the resume
     * method when the Atmosphere's application decide to resume the {@link HttpServletResponse}.
     * The returned value, of type
     * {@link AtmosphereServlet.Action}, tells the proprietary Comet {@link Servlet}
     * to resume (again), suspended or do nothing with the current {@link HttpServletResponse}.
     *
     * @param request  the {@link HttpServletRequest}
     * @param response the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action resumed(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        logger.debug("(resumed) invoked:\n HttpServletRequest: {}\n HttpServletResponse: {}", request, response);
        return action(request, response);
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the timedout
     * method when the underlying WebServer time out the {@link HttpServletResponse}.
     * The returned value, of type
     * {@link AtmosphereServlet.Action}, tells the proprietary Comet {@link Servlet}
     * to resume (again), suspended or do nothing with the current {@link HttpServletResponse}.
     *
     * @param request  the {@link HttpServletRequest}
     * @param response the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action timedout(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        AtmosphereResourceImpl re;
        long l = (Long) request.getAttribute(MAX_INACTIVE);
        if (l == -1) {
            // The closedDetector closed the connection.
            return timedoutAction;
        }
        request.setAttribute(MAX_INACTIVE, (long) -1);

        // Something went wrong.
        if (request == null || response == null) {
            logger.warn("Invalid Request/Response: {}/{}", request, response);
            return timedoutAction;
        }

        re = (AtmosphereResourceImpl) request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

        if (re != null && re.getAtmosphereResourceEvent().isSuspended()) {
            re.getAtmosphereResourceEvent().setIsResumedOnTimeout(true);

            Broadcaster b = re.getBroadcaster();
            if (b instanceof DefaultBroadcaster) {
                ((DefaultBroadcaster) b).broadcastOnResume(re);
            }

            if (re.getRequest().getAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT) != null) {
                re.getAtmosphereResourceEvent().setIsResumedOnTimeout(
                        (Boolean) re.getRequest().getAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT));
            }
            invokeAtmosphereHandler(re);
        }

        return timedoutAction;
    }

    void invokeAtmosphereHandler(AtmosphereResourceImpl r) throws IOException {
        HttpServletRequest req = r.getRequest();
        HttpServletResponse response = r.getResponse();
        String disableOnEvent = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.DISABLE_ONSTATE_EVENT);

        try {
            if (!r.getResponse().equals(response)) {
                logger.warn("Invalid response: {}", response);
            } else if (disableOnEvent == null || !disableOnEvent.equals(String.valueOf(true))) {
                AtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler =
                        (AtmosphereHandler<HttpServletRequest, HttpServletResponse>)
                                req.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
                atmosphereHandler.onStateChange(r.getAtmosphereResourceEvent());
            } else {
                r.getResponse().flushBuffer();
            }
        } catch (IOException ex) {
            try {
                r.onThrowable(ex);
            } catch (Throwable t) {
                logger.warn("failed calling onThrowable()", ex);
            }
        } finally {
            try {
                aliveRequests.remove(req);
                r.notifyListeners();
            } finally {
                destroyResource(r);
            }
        }
    }

    private void destroyResource(AtmosphereResourceImpl r) {
        r.removeEventListeners();
        try {
            r.getBroadcaster().removeAtmosphereResource(r);
        } catch (IllegalStateException ex) {
            logger.trace(ex.getMessage(), ex);
        }
        if (BroadcasterFactory.getDefault() != null) {
            BroadcasterFactory.getDefault().removeAllAtmosphereResource(r);
        }
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the cancelled
     * method when the underlying WebServer detect that the client closed
     * the connection.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public synchronized Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        AtmosphereResourceImpl re = null;
        long l = (Long) req.getAttribute(MAX_INACTIVE);
        if (l == -1) {
            // The closedDetector closed the connection.
            return timedoutAction;
        }
        req.setAttribute(MAX_INACTIVE, (long) -1);

        try {
            re = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
            if (re != null) {
                re.getAtmosphereResourceEvent().setCancelled(true);
                invokeAtmosphereHandler(re);
                re.setIsInScope(false);
            }
        } catch (Throwable ex) {
            // Something wrong happenned, ignore the exception
            logger.debug("failed to cancel resource: " + re, ex);
        } finally {
            try {
                aliveRequests.remove(req);
                if (re != null) {
                    re.notifyListeners();
                }
            } finally {
                if (re != null) {
                    destroyResource(re);
                }
            }
        }

        return cancelledAction;
    }

    void shutdown() {
        closedDetector.shutdownNow();
        for (AtmosphereResource<HttpServletRequest, HttpServletResponse> resource : aliveRequests.values()) {
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
}
