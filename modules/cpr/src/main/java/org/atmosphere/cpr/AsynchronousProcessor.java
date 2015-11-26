/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.cpr;

import org.atmosphere.container.Servlet30CometSupport;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.Action.TYPE.SKIP_ATMOSPHEREHANDLER;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_CREATE;
import static org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_HANDLER_WRAPPER;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_RESOURCE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;

/**
 * Base class which implement the semantics of suspending and resuming of a Comet/WebSocket Request.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class
        AsynchronousProcessor implements AsyncSupport<AtmosphereResourceImpl> {

    private static final Logger logger = LoggerFactory.getLogger(AsynchronousProcessor.class);
    protected static final Action timedoutAction = new Action(Action.TYPE.TIMEOUT);
    protected static final Action cancelledAction = new Action(Action.TYPE.CANCELLED);
    protected final AtmosphereConfig config;
    private EndpointMapper<AtmosphereHandlerWrapper> mapper;
    private final long closingTime;
    private final boolean isServlet30;
    private boolean closeOnCancel;

    public AsynchronousProcessor(AtmosphereConfig config) {
        this.config = config;
        closingTime = Long.valueOf(config.getInitParameter(ApplicationConfig.CLOSED_ATMOSPHERE_THINK_TIME, "0"));
        isServlet30 = Servlet30CometSupport.class.isAssignableFrom(this.getClass());
        closeOnCancel = config.getInitParameter(ApplicationConfig.CLOSE_STREAM_ON_CANCEL, false);
        config.startupHook(new AtmosphereConfig.StartupHook() {
            @Override
            public void started(AtmosphereFramework framework) {
                mapper = framework.endPointMapper();
            }
        });
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
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
     * Is {@link HttpSession} timeout removal supported
     *
     * @return true if supported
     */
    protected boolean allowSessionTimeoutRemoval() {
        return config.isSessionTimeoutRemovalAllowed();
    }

    /**
     * Return the container's name.
     */
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo();
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the suspended method when the first request comes in.
     * The returned value, of type {@link Action}, tells the proprietary Comet {@link Servlet} to suspended or not the
     * current {@link AtmosphereResponse}.
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

        if (!Utils.properProtocol(req)) {
            logger.debug("Invalid request state.");
            res.setStatus(501);
            res.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
            res.flushBuffer();
            return new Action();
        }

        if (Utils.webSocketEnabled(req) && !supportWebSocket()) {
            logger.warn("Websocket protocol not supported");
            res.setStatus(501);
            res.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
            res.flushBuffer();
            return new Action();
        }

        if (config.handlers().isEmpty()) {
            logger.error("No AtmosphereHandler found. Make sure you define it inside WEB-INF/atmosphere.xml or annotate using @___Service");
            throw new AtmosphereMappingException("No AtmosphereHandler found. Make sure you define it inside WEB-INF/atmosphere.xml or annotate using @___Service");
        }

        if (res.request() == null) {
            res.request(req);
        }

        if (supportSession()) {
            // Create the session needed to support the Resume
            // operation from disparate requests.
            HttpSession s = req.getSession(config.getInitParameter(PROPERTY_SESSION_CREATE, true));

            // https://github.com/Atmosphere/atmosphere/issues/2034
            try {
                if (s != null && s.isNew()) {
                    s.setAttribute(FrameworkConfig.BROADCASTER_FACTORY, config.getBroadcasterFactory());
                }
            } catch(IllegalStateException ex) {
                AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(req.resource());
                logger.warn("Session Expired for {}. Closing the connection", req.uuid(), ex);
                if (r != null) {
                    logger.trace("Ending request for {}", r.uuid());
                    endRequest(r, true);
                    return Action.CANCELLED;
                } else {
                    logger.trace("Sending error for {}", req.uuid());
                    res.setStatus(500);
                    res.addHeader(X_ATMOSPHERE_ERROR, "Session expired");
                    res.flushBuffer();
                    return new Action();
                }
            }
        }

        req.setAttribute(FrameworkConfig.SUPPORT_SESSION, supportSession());

        int tracing = 0;

        AtmosphereHandlerWrapper handlerWrapper = map(req);
        if (config.getBroadcasterFactory() == null) {
            logger.error("Atmosphere is misconfigured and will not work. BroadcasterFactory is null");
            return Action.CANCELLED;
        }
        AtmosphereResourceImpl resource = configureWorkflow(null, handlerWrapper, req, res);

        String v = req.getHeader(HeaderConfig.X_ATMO_BINARY);
        if (v != null) {
            resource.forceBinaryWrite(Boolean.valueOf(v));
        }

        // handler interceptor lists
        LinkedList<AtmosphereInterceptor> invokedInterceptors = handlerWrapper.interceptors;
        Action a = invokeInterceptors(invokedInterceptors, resource, tracing);
        if (a.type() != Action.TYPE.CONTINUE && a.type() != Action.TYPE.SKIP_ATMOSPHEREHANDLER) {
            return a;
        }

        try {
            // Remap occured.
            if (req.getAttribute(FrameworkConfig.NEW_MAPPING) != null) {
                req.removeAttribute(FrameworkConfig.NEW_MAPPING);
                handlerWrapper = map(req);
                if (handlerWrapper == null) {
                    logger.debug("Remap {}", resource.uuid());
                    throw new AtmosphereMappingException("Invalid state. No AtmosphereHandler maps request for " + req.getRequestURI());
                }
                resource = configureWorkflow(resource, handlerWrapper, req, res);
                resource.setBroadcaster(handlerWrapper.broadcaster);
            }

            //Unit test mock the request and will throw NPE.
            boolean skipAtmosphereHandler = req.getAttribute(SKIP_ATMOSPHEREHANDLER.name()) != null
                    ? (Boolean) req.getAttribute(SKIP_ATMOSPHEREHANDLER.name()) : Boolean.FALSE;
            if (!skipAtmosphereHandler) {
                try {
                    logger.trace("\t Last: {}", handlerWrapper.atmosphereHandler.getClass().getName());
                    handlerWrapper.atmosphereHandler.onRequest(resource);
                } catch (IOException t) {
                    resource.onThrowable(t);
                    throw t;
                }
            }
        } finally{
            postInterceptors(handlerWrapper != null? handlerWrapper.interceptors: invokedInterceptors, resource);
        }

        Action action = resource.action();
        if (supportSession() && allowSessionTimeoutRemoval() && action.type().equals(Action.TYPE.SUSPEND)) {
            // Do not allow times out.
            SessionTimeoutSupport.setupTimeout(config, req.getSession(config.getInitParameter(ApplicationConfig.PROPERTY_SESSION_CREATE, true)));
        }
        logger.trace("Action for {} was {} with transport " + req.getHeader(X_ATMOSPHERE_TRANSPORT), req.resource() != null ? req.resource().uuid() : "null", action);
        return action;
    }

    private AtmosphereResourceImpl configureWorkflow(AtmosphereResourceImpl resource,
                                                     AtmosphereHandlerWrapper handlerWrapper,
                                                     AtmosphereRequest req, AtmosphereResponse res) {

        // Check Broadcaster state. If destroyed, replace it.
        Broadcaster b = handlerWrapper.broadcaster;
        if (b.isDestroyed()) {
            BroadcasterFactory f = config.getBroadcasterFactory();
            synchronized (f) {
                f.remove(b, b.getID());
                try {
                    handlerWrapper.broadcaster = f.get(b.getID());
                } catch (IllegalStateException ex) {
                    // Something wrong occurred, let's not fail and loookup the value
                    logger.trace("", ex);
                    // fallback to lookup
                    handlerWrapper.broadcaster = f.lookup(b.getID(), true);
                }
            }
        }

        if (resource == null) {
            resource = (AtmosphereResourceImpl) req.getAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE);
        }

        if (resource == null) {
            // TODO: cast is dangerous
            resource = (AtmosphereResourceImpl)
                    config.resourcesFactory().create(config, handlerWrapper.broadcaster, res, this, handlerWrapper.atmosphereHandler);
        } else {
            // TODO: REDESIGN, UGLY.
            try {
                // Make sure it wasn't set before
                resource.getBroadcaster();
            } catch (IllegalStateException ex) {
                resource.setBroadcaster(handlerWrapper.broadcaster);
            }
            resource.atmosphereHandler(handlerWrapper.atmosphereHandler);
        }

        req.setAttribute(ATMOSPHERE_RESOURCE, resource);
        req.setAttribute(ATMOSPHERE_HANDLER_WRAPPER, handlerWrapper);
        req.setAttribute(SKIP_ATMOSPHEREHANDLER.name(), Boolean.FALSE);

        return resource;
    }

    protected void shutdown() {
        Collection<AtmosphereResource> c = config.resourcesFactory().findAll();
        for (AtmosphereResource r : c) {
            try {
                r.close();
            } catch (IOException e) {
                logger.trace("", e);
            }
        }
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
    }

    @Override
    public AsyncSupport complete(AtmosphereResourceImpl r) {
        return this;
    }

    private String path(AtmosphereRequest request) {
        String path;
        String pathInfo = null;
        try {
            pathInfo = request.getPathInfo();
        } catch (IllegalStateException ex) {
            // http://java.net/jira/browse/GRIZZLY-1301
        }

        if (pathInfo != null) {
            path = request.getServletPath() + pathInfo;
        } else {
            path = request.getServletPath();
        }

        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return path;
    }

    public Action invokeInterceptors(List<AtmosphereInterceptor> c, AtmosphereResource r, int tracing) {
        Action a = Action.CONTINUE;
        for (AtmosphereInterceptor arc : c) {

            if (!AtmosphereResourceImpl.class.cast(r).isInScope()) {
                logger.warn("Request closed during processing {} and transport {}", r.uuid(), r.transport());
                return Action.CANCELLED;
            }

            try {
                a = arc.inspect(r);
            } catch (Exception ex) {
                logger.error("Interceptor {} crashed. Processing will continue with other interceptor.", arc, ex);
                continue;
            }

            if (a == null) {
                logger.trace("Action was null for {}", arc);
                a = Action.CANCELLED;
            }

            boolean skip = a.type() == SKIP_ATMOSPHEREHANDLER;
            if (skip) {
                logger.trace("AtmosphereInterceptor {} asked to skip the AtmosphereHandler for {}", arc, r.uuid());
                r.getRequest().setAttribute(SKIP_ATMOSPHEREHANDLER.name(), Boolean.TRUE);
            }

            if (a.type() != Action.TYPE.CONTINUE) {
                logger.trace("Interceptor {} interrupted the dispatch for {} with " + a, arc, r.uuid());
                return a;
            }

            if (logger.isTraceEnabled()) {
                logger.trace("\t {}: {} for {}", new String[]{String.valueOf(tracing++), arc.getClass().getName(), r.uuid()});
            }
        }
        return a;
    }

    public void postInterceptors(List<AtmosphereInterceptor> c, AtmosphereResource r) {
        AtmosphereInterceptor arc = null;
        for (int i = c.size() - 1; i > -1; i--) {
            try {
                arc = c.get(i);
                arc.postInspect(r);
            } catch (Exception ex) {
                logger.error("Interceptor {} crashed. Processing will continue with other interceptor.", arc, ex);
                continue;
            }
        }
    }

    /**
     * Return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     *
     * @param req the {@link AtmosphereResponse}
     * @return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     * @throws javax.servlet.ServletException
     */
    protected AtmosphereHandlerWrapper map(AtmosphereRequest req) throws ServletException {
        AtmosphereHandlerWrapper atmosphereHandlerWrapper = mapper.map(req, config.handlers());
        if (atmosphereHandlerWrapper == null) {
            logger.debug("No AtmosphereHandler maps request for {} with mapping {}", req.getRequestURI(), config.handlers());
            throw new AtmosphereMappingException("No AtmosphereHandler maps request for " + req.getRequestURI());
        }

        return atmosphereHandlerWrapper;
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the resume method when the Atmosphere's application
     * decide to resume the {@link AtmosphereResponse}. The returned value, of type {@link Action}, tells the
     * proprietary Comet {@link Servlet} to resume (again), suspended or do nothing with the current {@link AtmosphereResponse}.
     *
     * @param request  the {@link AtmosphereRequest}
     * @param response the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action resumed(AtmosphereRequest request, AtmosphereResponse response)
            throws IOException, ServletException {

        AtmosphereResourceImpl r =
                (AtmosphereResourceImpl) request.getAttribute(ATMOSPHERE_RESOURCE);

        if (r == null) return Action.CANCELLED; // We are cancelled already

        AtmosphereHandler atmosphereHandler = r.getAtmosphereHandler();

        AtmosphereResourceEvent event = r.getAtmosphereResourceEvent();
        if (event != null && event.isResuming() && !event.isCancelled()) {
            synchronized (r) {
                atmosphereHandler.onStateChange(event);
            }
        }
        return Action.RESUME;
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the timedout method when the underlying WebServer time
     * out the {@link AtmosphereResponse}. The returned value, of type {@link Action}, tells the proprietary
     * Comet {@link Servlet} to resume (again), suspended or do nothing with the current {@link AtmosphereResponse}.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action timedout(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        logger.trace("Timing out {}", req);
        endRequest(AtmosphereResourceImpl.class.cast(req.resource()), false);
        return timedoutAction;
    }

    /**
     * Cancel or times out an {@link AtmosphereResource} by invoking it's associated {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
     *
     * @param r         an {@link AtmosphereResource}
     * @param cancelled true if cancelled, false if timedout
     * @return true if the operation was executed.
     */
    public boolean completeLifecycle(final AtmosphereResource r, boolean cancelled) {
        if (r != null && !r.isCancelled() && !AtmosphereResourceImpl.class.cast(r).getAndSetInClosingPhase()) {
            logger.trace("Finishing lifecycle for AtmosphereResource {}", r.uuid());
            final AtmosphereResourceImpl impl = AtmosphereResourceImpl.class.cast(r);
            try {
                if (impl.isCancelled()) {
                    logger.debug("{} is already cancelled", impl.uuid());
                    return false;
                }

                AtmosphereResourceEventImpl e = impl.getAtmosphereResourceEvent();
                // https://github.com/Atmosphere/atmosphere/issues/1756
                // Do not go inside Atmosphere once undeployed to avoid all kind of issues.
                if (config.framework().isDestroyed()) {
                    cancelled = true;
                }

                if (!e.isClosedByClient()) {
                    if (cancelled) {
                        e.setCancelled(cancelled);
                    } else {
                        e.setIsResumedOnTimeout(true);
                        Broadcaster b = r.getBroadcaster();
                        if (b instanceof DefaultBroadcaster) {
                            ((DefaultBroadcaster) b).broadcastOnResume(r);
                        }

                        // TODO: Was it there for legacy reason?
                        // impl.getAtmosphereResourceEvent().setIsResumedOnTimeout(impl.resumeOnBroadcast());
                    }
                }
                invokeAtmosphereHandler(impl);
            } catch (Throwable ex) {
                // Something wrong happened, ignore the exception
                logger.error("Failed to cancel resource: {}", impl.uuid(), ex);
            } finally {
                try {
                    impl.notifyListeners();
                    // Legacy code #1894 that may breaks Jersey when set to false on some old server.
                    if (closeOnCancel) {
                        try {
                            impl.getResponse(false).getOutputStream().close();
                        } catch (Throwable t) {
                            try {
                                impl.getResponse(false).getWriter().close();
                            } catch (Throwable t2) {
                            }
                        }
                    }
                    impl.setIsInScope(false);
                    impl.cancel();
                } catch (Throwable t) {
                    logger.debug("completeLifecycle", t);
                } finally {
                    impl._destroy();
                }
            }
            return true;
        } else {
            logger.trace("AtmosphereResource {} was already cancelled or gc", r != null ? r.uuid() : "null");
            return false;
        }
    }

    /**
     * Invoke the associated {@link AtmosphereHandler}. This method must be synchronized on an AtmosphereResource.
     *
     * @param r a {@link AtmosphereResourceImpl}
     * @throws IOException
     */
    protected void invokeAtmosphereHandler(AtmosphereResourceImpl r) throws IOException {
        AtmosphereRequest req = r.getRequest(false);
        try {
            // Rely on isInScope instead of synchronization https://github.com/Atmosphere/atmosphere/issues/1865
            if (r.isInScope()) {
                String disableOnEvent = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.DISABLE_ONSTATE_EVENT);
                r.getAtmosphereResourceEvent().setMessage(r.writeOnTimeout());
                try {
                    if (disableOnEvent == null || !disableOnEvent.equals(String.valueOf(true))) {
                        AtmosphereHandler atmosphereHandler = r.getAtmosphereHandler();

                        if (atmosphereHandler != null && r.isInScope()) {

                            try {
                                Utils.inject(r);
                            } catch (IllegalAccessException e) {
                                logger.warn("",e);
                            }

                            atmosphereHandler.onStateChange(r.getAtmosphereResourceEvent());
                        }
                    }
                } catch (IOException ex) {
                    try {
                        r.onThrowable(ex);
                    } catch (Throwable t) {
                        logger.warn("failed calling onThrowable()", ex);
                    }
                }
            } else {
                logger.trace("AtmosphereResource out of scope {}", r.uuid());
                return;
            }
        } finally {
            Utils.destroyMeteor(req);
        }
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the cancelled method when the underlying WebServer
     * detect that the client closed the connection.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action cancelled(final AtmosphereRequest req, final AtmosphereResponse res)
            throws IOException, ServletException {

        logger.trace("Cancelling {}", req);
        final AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(req.resource());
        // Leave a chance to the client to send the disconnect message before processing the connection
        if (closingTime > 0) {
            ExecutorsFactory.getScheduler(config).schedule(new Callable<Object>() {

                @Override
                public Object call() throws Exception {
                    endRequest(r, true);
                    return null;
                }
            }, closingTime, TimeUnit.MILLISECONDS);
        } else {
            if (completeLifecycle(req.resource(), true)) {
                config.framework().notify(Action.TYPE.CANCELLED, req, res);
            }
        }
        return cancelledAction;
    }

    public void endRequest(AtmosphereResourceImpl r, boolean cancel) {
        if (completeLifecycle(r, cancel)) {
            config.framework().notify(Action.TYPE.CANCELLED, r.getRequest(false), r.getResponse(false));
        }
    }

    @Override
    public boolean supportWebSocket() {
        return false;
    }

}
