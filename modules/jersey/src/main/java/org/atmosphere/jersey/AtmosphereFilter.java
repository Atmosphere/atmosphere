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
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Cluster;
import org.atmosphere.annotation.Publish;
import org.atmosphere.annotation.Resume;
import org.atmosphere.annotation.Schedule;
import org.atmosphere.annotation.Subscribe;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereEventLifecycle;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.cpr.Trackable;
import org.atmosphere.di.InjectorProvider;
import org.atmosphere.websocket.WebSocketSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    // For backward compatibility
    public final static String CONTAINER_RESPONSE = AtmosphereServlet.CONTAINER_RESPONSE;

    enum Action {
        SUSPEND, RESUME, BROADCAST, SUSPEND_RESUME,
        SCHEDULE_RESUME, RESUME_ON_BROADCAST, NONE, SCHEDULE, SUSPEND_RESPONSE,
        SUSPEND_TRACKABLE, SUBSCRIBE, SUBSCRIBE_TRACKABLE, PUBLISH
    }

    private
    @Context
    HttpServletRequest servletReq;

    private
    @Context
    UriInfo uriInfo;

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

        boolean resumeOnBroadcast(ContainerRequest request, boolean resumeOnBroadcast) {
            String transport = request.getHeaderValue("X-Atmosphere-Transport");
            if (transport != null && transport.equals("long-polling")) {
                return true;
            }
            return resumeOnBroadcast;
        }

        boolean outputJunk(ContainerRequest request, boolean outputJunk) {
            boolean webSocketEnabled = false;
            if (servletReq.getHeaders("Connection") != null && servletReq.getHeaders("Connection").hasMoreElements()) {
                String[] e = ((Enumeration<String>) servletReq.getHeaders("Connection")).nextElement().split(",");
                for (String upgrade : e) {
                    if (upgrade.trim().equalsIgnoreCase("Upgrade")) {
                        webSocketEnabled = true;
                        break;
                    }
                }
            }

            String transport = request.getHeaderValue("X-Atmosphere-Transport");
            if (webSocketEnabled) {
                return false;
            } else if (transport != null && transport.equals("long-polling")) {
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
        public ContainerResponse filter(ContainerRequest request, ContainerResponse response) {
            if (response.getMappedThrowable() != null) {
                return response;
            }

            AtmosphereResource<HttpServletRequest, HttpServletResponse> r =
                    (AtmosphereResource<HttpServletRequest, HttpServletResponse>) servletReq
                            .getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);

            boolean sessionSupported = (Boolean) servletReq.getAttribute
                    (AtmosphereServlet.SUPPORT_SESSION);

            switch (action) {
                case SUSPEND_RESPONSE:
                    SuspendResponse<?> s = SuspendResponse.class.cast(JResponseAsResponse.class.cast(response.getResponse()).getJResponse());

                    boolean outputJunk = outputJunk(request, s.outputComments());
                    boolean resumeOnBroadcast = resumeOnBroadcast(request, s.resumeOnBroadcast());

                    for (AtmosphereResourceEventListener el : s.listeners()) {
                        if (r instanceof AtmosphereEventLifecycle) {
                            ((AtmosphereEventLifecycle) r).addEventListener(el);
                        }
                    }

                    Broadcaster bc = s.broadcaster();
                    if (bc == null) {
                        bc = (Broadcaster) servletReq.getAttribute(INJECTED_BROADCASTER);
                    }

                    suspend(sessionSupported, resumeOnBroadcast, outputJunk,
                            translateTimeUnit(s.period().value(), s.period().timeUnit()), request, response, bc, r);

                    break;
                case SUBSCRIBE_TRACKABLE:
                case SUBSCRIBE:
                case SUSPEND:
                case SUSPEND_TRACKABLE:
                case SUSPEND_RESUME:
                    outputJunk = outputJunk(request, outputComments);
                    resumeOnBroadcast = resumeOnBroadcast(request, (action == Action.SUSPEND_RESUME));

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
                            c = (Class<Broadcaster>) Class.forName((String)servletReq.getAttribute(AtmosphereServlet.BROADCASTER_CLASS));
                        } catch (Throwable e) {
                            throw new IllegalStateException(e.getMessage());
                        }
                        broadcaster = BroadcasterFactory.getDefault().lookup(c, topic, true);
                    }

                    // Register our TrackableResource
                    boolean isTracked = response.getEntity() != null ? TrackableResource.class.isAssignableFrom(response.getEntity().getClass()) : false;
                    TrackableResource<? extends Trackable> trackableResource = null;

                    if (isTracked) {
                        trackableResource = TrackableResource.class.cast(response.getEntity());
                        TrackableSession.getDefault().track(trackableResource);
                        response.setEntity(trackableResource.entity());
                        response.getHttpHeaders().putSingle(TrackableResource.TRACKING_HEADER, trackableResource.trackingID());
                    }

                    suspend(sessionSupported, resumeOnBroadcast, outputJunk, timeout, request, response,
                            broadcaster, r);

                    // Associate the tracked resource.
                    if (isTracked && trackableResource != null) {
                        boolean isAresource = AtmosphereResource.class.isAssignableFrom(trackableResource.type()) ? true : false;
                        trackableResource.setResource(isAresource ? r : r.getBroadcaster());
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
                            c = (Class<Broadcaster>) Class.forName((String)servletReq.getAttribute(AtmosphereServlet.BROADCASTER_CLASS));
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

        void configureHeaders(ContainerResponse response) throws IOException {
            boolean webSocketSupported = servletReq.getAttribute(WebSocketSupport.WEBSOCKET_SUSPEND) != null;

            if (servletReq.getHeaders("Connection") != null && servletReq.getHeaders("Connection").hasMoreElements()) {
                String[] e = ((Enumeration<String>) servletReq.getHeaders("Connection")).nextElement().split(",");
                for (String upgrade : e) {
                    if (upgrade != null && upgrade.equalsIgnoreCase("Upgrade")) {
                        if (!webSocketSupported) {
                            response.getHttpHeaders().putSingle("X-Atmosphere-error", "Websocket protocol not supported");
                        }
                    }
                }
            }

            boolean injectCacheHeaders = (Boolean)servletReq.getAttribute(AtmosphereServlet.NO_CACHE_HEADERS);
            boolean enableAccessControl = (Boolean)servletReq.getAttribute(AtmosphereServlet.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);

            if (injectCacheHeaders) {
                // Set to expire far in the past.
                response.getHttpHeaders().putSingle("Expires", "-1");
                // Set standard HTTP/1.1 no-cache headers.
                response.getHttpHeaders().putSingle("Cache-Control", "no-store, no-cache, must-revalidate");
                // Set standard HTTP/1.0 no-cache header.
                response.getHttpHeaders().putSingle("Pragma", "no-cache");
            }

            if (enableAccessControl) {
                response.getHttpHeaders().putSingle("Access-Control-Allow-Origin", "*");
            }
        }

        void configureResumeOnBroadcast(Broadcaster b) {
            Iterator<AtmosphereResource<?, ?>> i = b.getAtmosphereResources().iterator();
            while (i.hasNext()) {
                HttpServletRequest r = (HttpServletRequest) i.next().getRequest();
                r.setAttribute(AtmosphereServlet.RESUME_ON_BROADCAST, true);
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

        void suspend(boolean sessionSupported,
                     boolean resumeOnBroadcast,
                     boolean comments,
                     long timeout,
                     ContainerRequest request,
                     ContainerResponse response,
                     Broadcaster bc,
                     AtmosphereResource<HttpServletRequest, HttpServletResponse> r) {

            // Force the status code to 200 events independently of the value of the entity (null or not)
            if (response.getStatus() == 204) {
                response.setStatus(200);
            }

            BroadcasterFactory broadcasterFactory = (BroadcasterFactory) servletReq
                    .getAttribute(AtmosphereServlet.BROADCASTER_FACTORY);

            // Do not add location header if already there.
            if (!sessionSupported && !resumeOnBroadcast && response.getHttpHeaders().getFirst("Location") == null) {
                String uuid = UUID.randomUUID().toString();

                response.getHttpHeaders().putSingle(
                        HttpHeaders.LOCATION,
                        uriInfo.getAbsolutePathBuilder().path(uuid).build(""));

                resumeCandidates.put(uuid, r);
                servletReq.setAttribute(RESUME_UUID, uuid);
                servletReq.setAttribute(RESUME_CANDIDATES, resumeCandidates);
            }

            if (bc == null) {
                bc = r.getBroadcaster();
            }

            if (sessionSupported && servletReq.getSession().getAttribute(SUSPENDED_RESOURCE) != null) {
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

                if ((scope == Suspend.SCOPE.REQUEST) && (bc.getScope() != Broadcaster.SCOPE.REQUEST)) {
                    bc.setScope(Broadcaster.SCOPE.REQUEST);
                }
            } else if ((scope == Suspend.SCOPE.REQUEST) && (bc.getScope() != Broadcaster.SCOPE.REQUEST)) {
                try {
                    String id = bc.getID();
                    bc.setID(bc.getClass().getSimpleName() + "-" + UUID.randomUUID());

                    // Re-generate a new one with proper scope.
                    bc = broadcasterFactory.get();
                    bc.setScope(Broadcaster.SCOPE.REQUEST);
                    bc.setID(id);
                } catch (Exception ex) {
                    logger.error("failed to instantiate broadcaster with factory: " + broadcasterFactory, ex);
                }
            }
            configureFilter(bc);
            r.setBroadcaster(bc);

            if (sessionSupported) {
                servletReq.getSession().setAttribute(SUSPENDED_RESOURCE, r);
                servletReq.getSession().setAttribute(AtmosphereServlet.CONTAINER_RESPONSE, response);
            }

            servletReq.setAttribute(SUSPENDED_RESOURCE, r);
            servletReq.setAttribute(AtmosphereServlet.CONTAINER_RESPONSE, response);

            logger.debug("Linking HttpServletRequest {} with ContainerResponse {}", servletReq, response);

            if (resumeOnBroadcast) {
                servletReq.setAttribute(AtmosphereServlet.RESUME_ON_BROADCAST, new Boolean(true));
            }

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
                if (entity != null) {
                    r.getResponse().setContentType(contentType != null ?
                            contentType.toString() : "text/html; charset=ISO-8859-1");
                }

                configureHeaders(response);
                if (comments && !resumeOnBroadcast) {
                    response.setEntity(AtmosphereResourceImpl.createCompatibleStringJunk());
                }

                if (entity != null) {
                    response.setEntity(entity);
                    response.write();
                }
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
            Class[] suspendTimeout = am.getAnnotation(Broadcast.class).value();

            if (am.getAnnotation(Broadcast.class).resumeOnBroadcast()) {
                f = new Filter(Action.RESUME_ON_BROADCAST, delay, 0, Suspend.SCOPE.APPLICATION, true, suspendTimeout, null);
            } else {
                f = new Filter(Action.BROADCAST, delay, 0, Suspend.SCOPE.APPLICATION, true, suspendTimeout, null);
            }

            list.addLast(f);

            if (am.isAnnotationPresent(Cluster.class)) {
                suspendTimeout = am.getAnnotation(Cluster.class).value();
                for (Class<ClusterBroadcastFilter> c : suspendTimeout) {
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
            f = new Filter( Action.PUBLISH, -1, -1, Suspend.SCOPE.APPLICATION,
                    false, null, am.getAnnotation(Publish.class).value());
            list.addFirst(f);
        }

        if (am.isAnnotationPresent(Resume.class)) {
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
