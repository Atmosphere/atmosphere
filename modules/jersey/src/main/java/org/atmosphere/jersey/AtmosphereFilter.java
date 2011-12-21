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
*/
package org.atmosphere.jersey;

import com.sun.jersey.api.JResponseAsResponse;
import com.sun.jersey.api.model.AbstractMethod;
import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;
import com.sun.jersey.spi.container.ContainerResponse;
import com.sun.jersey.spi.container.ContainerResponseFilter;
import com.sun.jersey.spi.container.ResourceFilter;
import com.sun.jersey.spi.container.ResourceFilterFactory;
import org.atmosphere.annotation.Asynchronous;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Cluster;
import org.atmosphere.annotation.Publish;
import org.atmosphere.annotation.Resume;
import org.atmosphere.annotation.Schedule;
import org.atmosphere.annotation.Subscribe;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereEventLifecycle;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.Trackable;
import org.atmosphere.di.InjectorProvider;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.atmosphere.cpr.HeaderConfig.CACHE_CONTROL;
import static org.atmosphere.cpr.HeaderConfig.EXPIRES;
import static org.atmosphere.cpr.HeaderConfig.JSONP_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.LONG_POLLING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.POLLING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.PRAGMA;
import static org.atmosphere.cpr.HeaderConfig.STREAMING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRACKING_ID;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;

/**
 * {@link ResourceFilterFactory} which intercept the response and appropriately
 * set the {@link AtmosphereResourceEvent} filed based on the annotation the application
 * has defined.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereFilter implements ResourceFilterFactory {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereFilter.class);
    public final static String SUSPENDED_RESOURCE = AtmosphereFilter.class.getName() + ".suspendedResource";
    public final static String RESUME_UUID = AtmosphereFilter.class.getName() + ".uuid";
    public final static String RESUME_CANDIDATES = AtmosphereFilter.class.getName() + ".resumeCandidates";
    public final static String INJECTED_BROADCASTER = AtmosphereFilter.class.getName() + "injectedBroadcaster";
    public final static String INJECTED_TRACKABLE = AtmosphereFilter.class.getName() + "injectedTrackable";

    enum Action {
        SUSPEND, RESUME, BROADCAST, SUSPEND_RESUME,
        SCHEDULE_RESUME, RESUME_ON_BROADCAST, NONE, SCHEDULE, SUSPEND_RESPONSE,
        SUSPEND_TRACKABLE, SUBSCRIBE, SUBSCRIBE_TRACKABLE, PUBLISH, ASYNCHRONOUS
    }

    private
    @Context
    HttpServletRequest servletReq;

    private
    @Context
    UriInfo uriInfo;

    private boolean useResumeAnnotation = false;

    private final ConcurrentHashMap<String, AtmosphereResource<HttpServletRequest, HttpServletResponse>> resumeCandidates =
            new ConcurrentHashMap<String, AtmosphereResource<HttpServletRequest, HttpServletResponse>>();

    private class Filter implements ResourceFilter, ContainerResponseFilter {

        private final Action action;
        private final long timeout;
        private final int waitFor;
        private final Suspend.SCOPE scope;
        private final Class<BroadcastFilter>[] filters;
        private Class<? extends AtmosphereResourceEventListener>[] listeners = null;
        private final boolean outputComments;
        private final ArrayList<ClusterBroadcastFilter> clusters
                = new ArrayList<ClusterBroadcastFilter>();
        private final String topic;

        protected Filter(Action action) {
            this(action, -1);
        }

        protected Filter(Action action, long timeout) {
            this(action, timeout, 0);
        }

        protected Filter(Action action, long timeout, int waitFor) {
            this(action, timeout, waitFor, Suspend.SCOPE.APPLICATION);
        }

        protected Filter(Action action, long timeout, int waitFor, Suspend.SCOPE scope) {
            this(action, timeout, waitFor, scope, true);
        }

        protected Filter(Action action, long timeout, int waitFor, Suspend.SCOPE scope, boolean outputComments) {
            this(action, timeout, waitFor, scope, outputComments, null, null);
        }

        protected Filter(Action action, long timeout, int waitFor, Suspend.SCOPE scope, boolean outputComments, Class<BroadcastFilter>[] filters, String topic) {
            this.action = action;
            this.timeout = timeout;
            this.scope = scope;
            this.outputComments = outputComments;
            this.waitFor = waitFor;
            this.filters = filters;
            this.topic = topic;
        }

        public ContainerRequestFilter getRequestFilter() {
            return null;
        }

        public ContainerResponseFilter getResponseFilter() {
            return this;
        }

        boolean resumeOnBroadcast(boolean resumeOnBroadcast) {
            String transport = servletReq.getHeader(X_ATMOSPHERE_TRANSPORT);
            if (transport != null && (transport.equals(JSONP_TRANSPORT) || transport.equals(LONG_POLLING_TRANSPORT))) {
                return true;
            }
            return resumeOnBroadcast;
        }

        boolean outputJunk(boolean outputJunk) {
            boolean webSocketEnabled = false;
            if (servletReq.getHeaders("Connection") != null && servletReq.getHeaders("Connection").hasMoreElements()) {
                String[] e = ((Enumeration<String>) servletReq.getHeaders("Connection")).nextElement().split(",");
                for (String upgrade : e) {
                    if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                        webSocketEnabled = true;
                        break;
                    }
                }
            }

            String transport = servletReq.getHeader(X_ATMOSPHERE_TRANSPORT);
            if (webSocketEnabled) {
                return false;
            } else if (transport != null && (transport.equals(JSONP_TRANSPORT) || transport.equals(LONG_POLLING_TRANSPORT))) {
                return false;
            }

            return outputJunk;
        }

        /**
         * Configure the {@link AtmosphereResourceEvent} state (suspend, resume, broadcast)
         * based on the annotation the web application has used.
         *
         * @param request  the {@link ContainerRequest}
         * @param response the {@link ContainerResponse}
         * @return the {@link ContainerResponse}
         */
        public ContainerResponse filter(final ContainerRequest request, final ContainerResponse response) {
            if (response.getMappedThrowable() != null) {
                return response;
            }

            AtmosphereResource<HttpServletRequest, HttpServletResponse> r =
                    (AtmosphereResource<HttpServletRequest, HttpServletResponse>) servletReq
                            .getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

            if (Boolean.parseBoolean((String) servletReq.getAttribute(ApplicationConfig.SUPPORT_LOCATION_HEADER))) {
                useResumeAnnotation = true;
            }

            switch (action) {
                case ASYNCHRONOUS:
                    // Force the status code to 200 events independently of the value of the entity (null or not)
                    if (response.getStatus() == 204) {
                        response.setStatus(200);
                    }

                    String transport = servletReq.getHeader(X_ATMOSPHERE_TRANSPORT);
                    String broadcasterName = servletReq.getHeader(topic);
                    if (transport == null || broadcasterName == null) {
                        throw new WebApplicationException(new IllegalStateException("Must specify transport using header value "
                                +  X_ATMOSPHERE_TRANSPORT
                                +  " and uuid " + X_ATMOSPHERE_TRACKING_ID));
                    }
                    String subProtocol = (String) servletReq.getAttribute(FrameworkConfig.WEBSOCKET_SUBPROTOCOL);

                    final boolean waitForResource = waitFor == -1 ? true : false;
                    final Broadcaster bcaster = BroadcasterFactory.getDefault().lookup(broadcasterName, true);

                    if (!transport.startsWith(POLLING_TRANSPORT) && subProtocol == null) {
                        boolean outputJunk = transport.equalsIgnoreCase(STREAMING_TRANSPORT);
                        final boolean resumeOnBroadcast = resumeOnBroadcast(false);

                        for (Class<? extends AtmosphereResourceEventListener> listener : listeners) {
                            try {
                                AtmosphereResourceEventListener el = listener.newInstance();
                                InjectorProvider.getInjector().inject(el);
                                if (r instanceof AtmosphereEventLifecycle) {
                                    r.addEventListener(el);
                                }
                            } catch (Throwable t) {
                                throw new WebApplicationException(
                                        new IllegalStateException("Invalid AtmosphereResourceEventListener " + listener));
                            }
                        }
                        final Object entity = response.getEntity();

                        r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                            @Override
                            public void onSuspend(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
                                try {
                                    if (entity != null) {
                                        if (waitForResource) {
                                            bcaster.awaitAndBroadcast(entity, 30, TimeUnit.SECONDS);
                                        } else {
                                            bcaster.broadcast(entity);
                                        }
                                    }
                                } finally {
                                    event.getResource().removeEventListener(this);
                                }
                            }
                        });

                        if (resumeOnBroadcast) {
                            servletReq.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, new Boolean(true));
                        }

                        r.setBroadcaster(bcaster);
                        executeSuspend(r, timeout, outputJunk, resumeOnBroadcast, null, request, response, false);
                    } else {
                        Object entity = response.getEntity();
                        if (waitForResource) {
                            bcaster.awaitAndBroadcast(entity, 30, TimeUnit.SECONDS);
                        } else {
                            bcaster.broadcast(entity);
                        }

                        if (subProtocol == null) {
                            try {
                                if (Callable.class.isAssignableFrom(entity.getClass())) {
                                    entity = Callable.class.cast(entity).call();
                                }
                                response.setEntity(entity);
                                response.write();
                            } catch (Throwable t) {
                                logger.debug("Error running Callable", t);
                                response.setEntity(null);
                            }
                        } else {
                            response.setEntity(null);
                        }
                    }
                    break;
                case SUSPEND_RESPONSE:
                    SuspendResponse<?> s = SuspendResponse.class.cast(JResponseAsResponse.class.cast(response.getResponse()).getJResponse());

                    boolean outputJunk = outputJunk(s.outputComments());
                    boolean resumeOnBroadcast = resumeOnBroadcast(s.resumeOnBroadcast());

                    for (AtmosphereResourceEventListener el : s.listeners()) {
                        if (r instanceof AtmosphereEventLifecycle) {
                            r.addEventListener(el);
                        }
                    }

                    Broadcaster bc = s.broadcaster();
                    if (bc == null && s.scope() != Suspend.SCOPE.REQUEST) {
                        bc = (Broadcaster) servletReq.getAttribute(INJECTED_BROADCASTER);
                    }

                    boolean supportTrackable = servletReq.getAttribute(ApplicationConfig.SUPPORT_TRACKABLE) != null;
                    // Register our TrackableResource
                    boolean isTracked = response.getEntity() != null ? TrackableResource.class.isAssignableFrom(response.getEntity().getClass()) : supportTrackable;

                    TrackableResource<? extends Trackable> trackableResource = null;
                    if (isTracked) {
                        trackableResource = preTrack(request, response);
                    }

                    suspend(resumeOnBroadcast, outputJunk,
                            translateTimeUnit(s.period().value(), s.period().timeUnit()), request, response, bc, r, s.scope());

                    // Associate the tracked resource.
                    if (isTracked && trackableResource != null) {
                        postTrack(trackableResource, r);
                    }

                    break;
                case SUBSCRIBE_TRACKABLE:
                case SUBSCRIBE:
                case SUSPEND:
                case SUSPEND_TRACKABLE:
                case SUSPEND_RESUME:
                    outputJunk = outputJunk(outputComments);
                    resumeOnBroadcast = resumeOnBroadcast((action == Action.SUSPEND_RESUME));

                    for (Class<? extends AtmosphereResourceEventListener> listener : listeners) {
                        try {
                            AtmosphereResourceEventListener el = listener.newInstance();
                            InjectorProvider.getInjector().inject(el);
                            if (r instanceof AtmosphereEventLifecycle) {
                                ((AtmosphereEventLifecycle) r).addEventListener(el);
                            }
                        } catch (Throwable t) {
                            throw new WebApplicationException(
                                    new IllegalStateException("Invalid AtmosphereResourceEventListener " + listener, t));
                        }
                    }

                    Broadcaster broadcaster = (Broadcaster) servletReq.getAttribute(INJECTED_BROADCASTER);
                    // @Subscribe
                    if (action == Action.SUBSCRIBE) {
                        Class<Broadcaster> c = null;
                        try {
                            c = (Class<Broadcaster>) Class.forName((String) servletReq.getAttribute(ApplicationConfig.BROADCASTER_CLASS));
                        } catch (Throwable e) {
                            throw new IllegalStateException(e.getMessage());
                        }
                        broadcaster = BroadcasterFactory.getDefault().lookup(c, topic, true);
                    }

                    // Tracking is enabled by default
                    supportTrackable = servletReq.getAttribute(ApplicationConfig.SUPPORT_TRACKABLE) != null;
                    // Register our TrackableResource
                    isTracked = response.getEntity() != null ? TrackableResource.class.isAssignableFrom(response.getEntity().getClass()) : supportTrackable;

                    if (isTracked) {
                        trackableResource = preTrack(request, response);
                    } else {
                        trackableResource = null;
                    }

                    suspend(resumeOnBroadcast, outputJunk, timeout, request, response,
                            broadcaster, r, scope);

                    // Associate the tracked resource.
                    if (isTracked && trackableResource != null) {
                        postTrack(trackableResource, r);
                    }
                    break;
                case RESUME:
                    if (response.getEntity() != null) {
                        try {
                            response.write();
                        } catch (IOException ex) {
                            throw new WebApplicationException(ex);
                        }
                    }

                    boolean sessionSupported = (Boolean) servletReq.getAttribute(FrameworkConfig.SUPPORT_SESSION);
                    if (sessionSupported) {
                        r = (AtmosphereResource) servletReq.getSession().getAttribute(SUSPENDED_RESOURCE);
                    } else {
                        String path = response.getContainerRequest().getPath();
                        r = resumeCandidates.remove(path.substring(path.lastIndexOf("/") + 1));
                    }

                    if (r != null) {
                        resume(r);
                    } else {
                        throw new WebApplicationException(
                                new IllegalStateException("Unable to retrieve suspended Response. " +
                                        "Either session-support is not enabled in atmosphere.xml or the" +
                                        "path used to resume is invalid."));

                    }
                    break;
                case BROADCAST:
                case PUBLISH:
                case RESUME_ON_BROADCAST:
                    AtmosphereResource ar = (AtmosphereResource) servletReq.getAttribute(SUSPENDED_RESOURCE);
                    if (ar != null) {
                        r = ar;
                    }

                    if (action == Action.PUBLISH) {
                        Class<Broadcaster> c = null;
                        try {
                            c = (Class<Broadcaster>) Class.forName((String) servletReq.getAttribute(ApplicationConfig.BROADCASTER_CLASS));
                        } catch (Throwable e) {
                            throw new IllegalStateException(e.getMessage());
                        }
                        r.setBroadcaster(BroadcasterFactory.getDefault().lookup(c, topic, true));
                    }

                    broadcast(response, r, timeout);
                    break;
                case SCHEDULE:
                case SCHEDULE_RESUME:
                    Object o = response.getEntity();
                    Broadcaster b = r.getBroadcaster();
                    if (response.getEntity() instanceof Broadcastable) {
                        b = ((Broadcastable) response.getEntity()).getBroadcaster();
                        o = ((Broadcastable) response.getEntity()).getMessage();
                        response.setEntity(((Broadcastable) response.getEntity()).getResponseMessage());
                    }

                    if (response.getEntity() != null) {
                        try {
                            response.write();
                        } catch (IOException ex) {
                            throw new WebApplicationException(ex);
                        }
                    }

                    if (action == Action.SCHEDULE_RESUME) {
                        configureResumeOnBroadcast(b);
                    }

                    b.scheduleFixedBroadcast(o, waitFor, timeout, TimeUnit.SECONDS);
                    break;
            }

            return response;
        }

        TrackableResource preTrack(ContainerRequest request, ContainerResponse response) {
            TrackableResource<? extends Trackable> trackableResource = TrackableResource.class.cast(response.getEntity());

            if (trackableResource == null) {
                trackableResource = new TrackableResource<AtmosphereResource>(AtmosphereResource.class, servletReq.getHeader(X_ATMOSPHERE_TRACKING_ID), "");
            } else {
                response.setEntity(trackableResource.entity());
            }

            String trackableUUID = request.getHeaderValue(X_ATMOSPHERE_TRACKING_ID);
            if (trackableUUID == null && trackableResource.trackingID() != null) {
                trackableUUID = trackableResource.trackingID();
            } else if (trackableUUID == null) {
                trackableUUID = UUID.randomUUID().toString();
            }
            trackableResource.setTrackingID(trackableUUID);

            TrackableSession.getDefault().track(trackableResource);

            response.getHttpHeaders().putSingle(X_ATMOSPHERE_TRACKING_ID, trackableResource.trackingID());
            servletReq.setAttribute(X_ATMOSPHERE_TRACKING_ID, trackableResource.trackingID());
            return trackableResource;
        }

        void postTrack(TrackableResource trackableResource, AtmosphereResource r) {
            boolean isAresource = AtmosphereResource.class.isAssignableFrom(trackableResource.type()) ? true : false;
            trackableResource.setResource(isAresource ? r : r.getBroadcaster());
        }

        Response.ResponseBuilder configureHeaders(Response.ResponseBuilder b) throws IOException {
            boolean webSocketSupported = servletReq.getAttribute(WebSocket.WEBSOCKET_SUSPEND) != null;

            if (servletReq.getHeaders("Connection") != null && servletReq.getHeaders("Connection").hasMoreElements()) {
                String[] e = ((Enumeration<String>) servletReq.getHeaders("Connection")).nextElement().split(",");
                for (String upgrade : e) {
                    if (upgrade != null && upgrade.equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                        if (!webSocketSupported) {
                            b = b.header(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                        }
                    }
                }
            }

            boolean injectCacheHeaders = (Boolean) servletReq.getAttribute(ApplicationConfig.NO_CACHE_HEADERS);
            boolean enableAccessControl = (Boolean) servletReq.getAttribute(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);

            if (injectCacheHeaders) {
                // Set to expire far in the past.
                b = b.header(EXPIRES, "-1");
                // Set standard HTTP/1.1 no-cache headers.
                b = b.header(CACHE_CONTROL, "no-store, no-cache, must-revalidate");
                // Set standard HTTP/1.0 no-cache header.
                b = b.header(PRAGMA, "no-cache");
            }

            if (enableAccessControl) {
                b = b.header(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                b = b.header(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }
            return b;
        }

        void configureResumeOnBroadcast(Broadcaster b) {
            Iterator<AtmosphereResource<?, ?>> i = b.getAtmosphereResources().iterator();
            while (i.hasNext()) {
                HttpServletRequest r = (HttpServletRequest) i.next().getRequest();
                r.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, true);
            }
        }

        void configureFilter(Broadcaster bc) {
            if (bc == null) throw new WebApplicationException(new IllegalStateException("Broadcaster cannot be null"));

            /**
             * Here we can't predict if it's the same set of filter shared across all Broadcaster as
             * Broadcaster can have their own BroadcasterConfig instance.
             */
            BroadcasterConfig c = bc.getBroadcasterConfig();
            // Already configured
            if (c.hasFilters()) {
                return;
            }

            // Always the first one, before any transformation/filtering
            for (ClusterBroadcastFilter cbf : clusters) {
                cbf.setBroadcaster(bc);
                c.addFilter(cbf);
            }

            BroadcastFilter f = null;
            if (filters != null) {
                for (Class<BroadcastFilter> filter : filters) {
                    try {
                        f = filter.newInstance();
                        InjectorProvider.getInjector().inject(f);
                    } catch (Throwable t) {
                        logger.warn("Invalid @BroadcastFilter: " + filter, t);
                    }
                    c.addFilter(f);
                }
            }
        }

        private void setListeners(Class<? extends AtmosphereResourceEventListener>[] listeners) {
            this.listeners = listeners;
        }

        void broadcast(ContainerResponse r, AtmosphereResource ar, long delay) {
            Object o = r.getEntity();

            Broadcaster b = ar.getBroadcaster();
            Object msg = o;
            String returnMsg = null;
            // Something went wrong if null.
            if (o instanceof Broadcastable) {
                if (((Broadcastable) o).getBroadcaster() != null) {
                    b = ((Broadcastable) o).getBroadcaster();
                }
                msg = ((Broadcastable) o).getMessage();
                returnMsg = ((Broadcastable) o).getResponseMessage().toString();
            }

            if (action == Action.RESUME_ON_BROADCAST) {
                configureResumeOnBroadcast(b);
            }

            if (o != null) {
                addFilter(b);
                try {
                    r.setEntity(msg);
                    if (msg == null) return;

                    if (delay == -1) {
                        Future<Object> f = b.broadcast(msg);
                        if (f == null) return;
                        Object t = f.get();
                        if (o instanceof Broadcastable) {
                            r.setEntity(returnMsg);
                        }
                    } else if (delay == 0) {
                        b.delayBroadcast(msg);
                    } else {
                        b.delayBroadcast(msg, delay, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ex) {
                    logger.error("broadcast interrupted", ex);
                } catch (ExecutionException ex) {
                    logger.error("execution exception during broadcast", ex);
                }
            }
        }

        void addFilter(Broadcaster bc) {
            configureFilter(bc);
        }

        void resume(AtmosphereResource resource) {
            resource.resume();
        }

        void addCluster(ClusterBroadcastFilter f) {
            clusters.add(f);
        }

        void suspend(boolean resumeOnBroadcast,
                     boolean comments,
                     long timeout,
                     ContainerRequest request,
                     ContainerResponse response,
                     Broadcaster bc,
                     AtmosphereResource<HttpServletRequest, HttpServletResponse> r,
                     Suspend.SCOPE localScope) {

            // Force the status code to 200 events independently of the value of the entity (null or not)
            if (response.getStatus() == 204) {
                response.setStatus(200);
            }

            BroadcasterFactory broadcasterFactory = (BroadcasterFactory) servletReq
                    .getAttribute(ApplicationConfig.BROADCASTER_FACTORY);

            boolean sessionSupported = (Boolean) servletReq.getAttribute(FrameworkConfig.SUPPORT_SESSION);
            URI location = null;
            // Do not add location header if already there.
            if (useResumeAnnotation && !sessionSupported && !resumeOnBroadcast && response.getHttpHeaders().getFirst("Location") == null) {
                String uuid = UUID.randomUUID().toString();

                location = uriInfo.getAbsolutePathBuilder().path(uuid).build("");

                resumeCandidates.put(uuid, r);
                servletReq.setAttribute(RESUME_UUID, uuid);
                servletReq.setAttribute(RESUME_CANDIDATES, resumeCandidates);
            }

            if (bc == null && localScope != Suspend.SCOPE.REQUEST) {
                bc = r.getBroadcaster();
            }

            if (sessionSupported && localScope != Suspend.SCOPE.REQUEST && servletReq.getSession().getAttribute(SUSPENDED_RESOURCE) != null) {
                AtmosphereResource<HttpServletRequest, HttpServletResponse> cached =
                        (AtmosphereResource) servletReq.getSession().getAttribute(SUSPENDED_RESOURCE);
                bc = cached.getBroadcaster();
                // Just in case something went wrong.
                try {
                    bc.removeAtmosphereResource(cached);
                } catch (IllegalStateException ex) {
                    logger.trace(ex.getMessage(), ex);
                }
            }

            if (response.getEntity() instanceof Broadcastable) {
                Broadcastable b = (Broadcastable) response.getEntity();
                bc = b.getBroadcaster();
                response.setEntity(b.getResponseMessage());
            }

            if ((localScope == Suspend.SCOPE.REQUEST) && bc == null) {
                if (bc == null) {
                    try {
                        String id = servletReq.getHeader(X_ATMOSPHERE_TRACKING_ID);
                        if (id == null) {
                            id = UUID.randomUUID().toString();
                        }

                        bc = broadcasterFactory.get(id);
                        bc.setScope(Broadcaster.SCOPE.REQUEST);
                    } catch (Exception ex) {
                        logger.error("failed to instantiate broadcaster with factory: " + broadcasterFactory, ex);
                    }
                } else {
                    bc.setScope(Broadcaster.SCOPE.REQUEST);
                }
            }
            r.setBroadcaster(bc);

            if (resumeOnBroadcast) {
                servletReq.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, new Boolean(true));
            }

            executeSuspend(r, timeout, comments, resumeOnBroadcast, location, request, response, true);

        }

        void executeSuspend(AtmosphereResource r,
                            long timeout,
                            boolean comments,
                            boolean resumeOnBroadcast,
                            URI location,
                            ContainerRequest request,
                            ContainerResponse response,
                            boolean flushEntity) {

            boolean sessionSupported = (Boolean) servletReq.getAttribute(FrameworkConfig.SUPPORT_SESSION);
            configureFilter(r.getBroadcaster());
            if (sessionSupported) {
                servletReq.getSession().setAttribute(SUSPENDED_RESOURCE, r);
                servletReq.getSession().setAttribute(FrameworkConfig.CONTAINER_RESPONSE, response);
            }

            servletReq.setAttribute(SUSPENDED_RESOURCE, r);
            servletReq.setAttribute(FrameworkConfig.CONTAINER_RESPONSE, response);

            logger.debug("Linked HttpServletRequest {} with ContainerResponse {}", servletReq, response);
            // Set the content-type based on the returned entity.
            try {
                MediaType contentType = response.getMediaType();
                if (contentType == null && response.getEntity() != null) {
                    LinkedList<MediaType> l = new LinkedList<MediaType>();
                    // Will retrun the first
                    l.add(request.getAcceptableMediaType(new LinkedList<MediaType>()));
                    contentType = response.getMessageBodyWorkers().getMessageBodyWriterMediaType(
                            response.getEntity().getClass(),
                            response.getEntityType(),
                            response.getAnnotations(),
                            l);

                    if (contentType == null ||
                            contentType.isWildcardType() || contentType.isWildcardSubtype())
                        contentType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
                }

                Object entity = response.getEntity();

                Response.ResponseBuilder b = Response.ok();
                b = configureHeaders(b);
                if (entity != null) {
                    b = b.header("Content-Type", contentType != null ?
                            contentType.toString() : "text/html; charset=ISO-8859-1");
                    servletReq.setAttribute(FrameworkConfig.EXPECTED_CONTENT_TYPE, contentType.toString());
                }

                boolean eclipse362468 = false;
                String serverInfo = r.getAtmosphereConfig().getServletContext().getServerInfo();
                if (serverInfo.indexOf("jetty") != -1) {
                    String[] jettyVersion = serverInfo.substring(6).split("\\.");
                    // https://bugs.eclipse.org/bugs/show_bug.cgi?id=362468
                    eclipse362468 = ((Integer.valueOf(jettyVersion[0]) == 8 && Integer.valueOf(jettyVersion[1]) == 0 && Integer.valueOf(jettyVersion[2]) > 1))
                            || ((Integer.valueOf(jettyVersion[0]) == 7 && Integer.valueOf(jettyVersion[1]) == 5 && Integer.valueOf(jettyVersion[2]) == 4));

                    if (comments && eclipse362468) {
                        logger.debug("Padding response is disabled to workaround https://bugs.eclipse.org/bugs/show_bug.cgi?id=362468");
                    }
                }

                if (!eclipse362468 && comments && !resumeOnBroadcast) {
                    String padding = (String) servletReq.getAttribute(ApplicationConfig.STREAMING_PADDING_MODE);
                    String paddingData = AtmosphereResourceImpl.createStreamingPadding(padding);

                    if (location != null) {
                        b = b.header(HttpHeaders.LOCATION, location);
                        location = null;
                    }
                    response.setResponse(b.entity(paddingData).build());
                    response.write();
                }

                if (entity != null && flushEntity) {
                    try {
                        if (Callable.class.isAssignableFrom(entity.getClass())) {
                            entity = Callable.class.cast(entity).call();
                        }
                    } catch (Throwable t) {
                        logger.error("Error executing callable {}", entity);
                        entity = null;
                    }

                    if (location != null) {
                        b = b.header(HttpHeaders.LOCATION, location);
                    }
                    response.setResponse(b.entity(entity).build());
                    response.write();
                }

                response.setEntity(null);
                r.suspend(timeout, false);
            } catch (IOException ex) {
                throw new WebApplicationException(ex);
            }
        }
    }

    /**
     * Create a {@link ResourceFilter} which contains the information about the
     * annotation being processed.
     * <p/>
     * XXX Need to filter invalid mix of annotation.
     *
     * @param am an {@link AbstractMethod}
     * @return a List of {@link ResourceFilter} to invoke.
     */
    public List<ResourceFilter> create(AbstractMethod am) {
        LinkedList<ResourceFilter> list = new LinkedList<ResourceFilter>();
        Filter f;

        if (logger.isDebugEnabled()) {
            for (Annotation annotation : am.getAnnotations()) {
                logger.debug("AtmosphereFilter processing annotation: {}", annotation);
            }
        }

        if (am.getMethod() == null) {
            return null;
        }

        if (SuspendResponse.class.isAssignableFrom(am.getMethod().getReturnType())) {
            list.addLast(new Filter(Action.SUSPEND_RESPONSE));
            return list;
        }

        if (am.isAnnotationPresent(Broadcast.class)) {
            int delay = am.getAnnotation(Broadcast.class).delay();
            Class[] broadcastFilter = am.getAnnotation(Broadcast.class).value();

            if (am.getAnnotation(Broadcast.class).resumeOnBroadcast()) {
                f = new Filter(Action.RESUME_ON_BROADCAST, delay, 0, Suspend.SCOPE.APPLICATION, true, broadcastFilter, null);
            } else {
                f = new Filter(Action.BROADCAST, delay, 0, Suspend.SCOPE.APPLICATION, true, broadcastFilter, null);
            }

            list.addLast(f);

            if (am.isAnnotationPresent(Cluster.class)) {
                broadcastFilter = am.getAnnotation(Cluster.class).value();
                for (Class<ClusterBroadcastFilter> c : broadcastFilter) {
                    try {
                        ClusterBroadcastFilter cbf = c.newInstance();
                        InjectorProvider.getInjector().inject(cbf);
                        cbf.setUri(am.getAnnotation(Cluster.class).name());
                        f.addCluster(cbf);
                    } catch (Throwable t) {
                        logger.warn("Invalid ClusterBroadcastFilter", t);
                    }
                }
            }
        }

        if (am.isAnnotationPresent(Asynchronous.class)) {
            int suspendTimeout = am.getAnnotation(Asynchronous.class).period();
            Class[] broadcastFilter = am.getAnnotation(Asynchronous.class).broadcastFilter();

            boolean wait = am.getAnnotation(Asynchronous.class).waitForResource();
            f = new Filter(Action.ASYNCHRONOUS, suspendTimeout, wait ? -1 : 0, null, false, broadcastFilter, am.getAnnotation(Asynchronous.class).header());
            f.setListeners(am.getAnnotation(Asynchronous.class).eventListeners());
            list.addFirst(f);
        }

        if (am.isAnnotationPresent(Suspend.class)) {

            long suspendTimeout = am.getAnnotation(Suspend.class).period();
            TimeUnit tu = am.getAnnotation(Suspend.class).timeUnit();
            suspendTimeout = translateTimeUnit(suspendTimeout, tu);

            Suspend.SCOPE scope = am.getAnnotation(Suspend.class).scope();
            boolean outputComments = am.getAnnotation(Suspend.class).outputComments();

            boolean trackable = false;
            if (TrackableResource.class.isAssignableFrom(am.getMethod().getReturnType())) {
                trackable = true;
            }

            if (am.getAnnotation(Suspend.class).resumeOnBroadcast()) {
                f = new Filter(trackable ? Action.SUSPEND_TRACKABLE : Action.SUSPEND_RESUME, suspendTimeout, 0, scope, outputComments);
            } else {
                f = new Filter(trackable ? Action.SUSPEND_TRACKABLE : Action.SUSPEND, suspendTimeout, 0, scope, outputComments);
            }
            f.setListeners(am.getAnnotation(Suspend.class).listeners());

            list.addFirst(f);
        }

        if (am.isAnnotationPresent(Subscribe.class)) {
            boolean trackable = false;
            if (TrackableResource.class.isAssignableFrom(am.getMethod().getReturnType())) {
                trackable = true;
            }

            f = new Filter(trackable ? Action.SUBSCRIBE_TRACKABLE : Action.SUBSCRIBE, 30000, -1, Suspend.SCOPE.APPLICATION,
                    false, null, am.getAnnotation(Subscribe.class).value());
            f.setListeners(am.getAnnotation(Subscribe.class).listeners());

            list.addFirst(f);
        }

        if (am.isAnnotationPresent(Publish.class)) {
            f = new Filter(Action.PUBLISH, -1, -1, Suspend.SCOPE.APPLICATION,
                    false, null, am.getAnnotation(Publish.class).value());
            list.addFirst(f);
        }

        if (am.isAnnotationPresent(Resume.class)) {
            useResumeAnnotation = true;
            int suspendTimeout = am.getAnnotation(Resume.class).value();
            list.addFirst(new Filter(Action.RESUME, suspendTimeout));
        }

        if (am.isAnnotationPresent(Schedule.class)) {
            int period = am.getAnnotation(Schedule.class).period();
            int waitFor = am.getAnnotation(Schedule.class).waitFor();

            if (am.getAnnotation(Schedule.class).resumeOnBroadcast()) {
                list.addFirst(new Filter(Action.SCHEDULE_RESUME, period, waitFor));
            } else {
                list.addFirst(new Filter(Action.SCHEDULE, period, waitFor));
            }
        }

        // Nothing, normal Jersey application.
        return list.size() > 0 ? list : null;
    }

    private long translateTimeUnit(long period, TimeUnit tu) {
        if (period == -1) return period;

        switch (tu) {
            case SECONDS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.SECONDS);
            case MINUTES:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.MINUTES);
            case HOURS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.HOURS);
            case DAYS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.DAYS);
            case MILLISECONDS:
                return period;
            case MICROSECONDS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.MICROSECONDS);
            case NANOSECONDS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.NANOSECONDS);
        }
        return period;
    }

}

