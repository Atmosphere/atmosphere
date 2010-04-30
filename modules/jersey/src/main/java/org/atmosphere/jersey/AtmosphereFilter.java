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

import com.sun.jersey.api.model.AbstractMethod;
import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;
import com.sun.jersey.spi.container.ContainerResponse;
import com.sun.jersey.spi.container.ContainerResponseFilter;
import com.sun.jersey.spi.container.ResourceFilter;
import com.sun.jersey.spi.container.ResourceFilterFactory;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Cluster;
import org.atmosphere.annotation.Resume;
import org.atmosphere.annotation.Schedule;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereEventLifecycle;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.util.LoggerUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ResourceFilterFactory} which intercept the response and appropriately
 * set the {@link AtmosphereResourceEvent} filed based on the annotation the application
 * has defined.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereFilter implements ResourceFilterFactory {

    private Logger logger = LoggerUtils.getLogger();

    public final static String CONTAINER_RESPONSE = "cr";

    public final static String SUSPENDED_RESOURCE = AtmosphereFilter.class.getName() + ".suspendedResource";
    public final static String RESUME_UUID = AtmosphereFilter.class.getName() + ".uuid";
    public final static String RESUME_CANDIDATES = AtmosphereFilter.class.getName() + ".resumeCandidates";
    public final static String INJECTED_BROADCASTER = AtmosphereFilter.class.getName() + "injectedBroadcaster";

    static enum Action {
        SUSPEND, RESUME, BROADCAST, SUSPEND_RESUME,
        SCHEDULE_RESUME, RESUME_ON_BROADCAST, NONE, SCHEDULE
    }

    private @Context HttpServletRequest servletReq;

    private @Context UriInfo uriInfo;

    private final ConcurrentHashMap<String, AtmosphereResource> resumeCandidates =
            new ConcurrentHashMap<String, AtmosphereResource>();

    private class Filter implements ResourceFilter, ContainerResponseFilter {

        private final Action action;
        private final int value;
        private final int waitFor;
        private final Suspend.SCOPE scope;
        private final Class<org.atmosphere.cpr.BroadcastFilter>[] filters;
        private Class<? extends AtmosphereResourceEventListener>[] listeners = null;
        private final boolean outputComments;
        private final ArrayList<ClusterBroadcastFilter> clusters
                = new ArrayList<ClusterBroadcastFilter>();
        private final List<MediaType> mediaTypes = new LinkedList<MediaType>();

        protected Filter(Action action) {
            this(action, -1);
        }

        protected Filter(Action action, int value) {
            this(action, value, 0);
        }

        protected Filter(Action action, int value, int waitFor) {
            this(action,value, waitFor, Suspend.SCOPE.APPLICATION);
        }

        protected Filter(Action action, int value, int waitFor, Suspend.SCOPE scope) {
            this(action, value, waitFor, Suspend.SCOPE.APPLICATION, true);
        }

        protected Filter(Action action, int value, int waitFor, Suspend.SCOPE scope, boolean outputComments) {
            this(action,value,waitFor,scope,outputComments,null);
        }

        protected Filter(Action action, int value, int waitFor, Suspend.SCOPE scope, boolean outputComments, Class<org.atmosphere.cpr.BroadcastFilter>[] filters) {
            this.action = action;
            this.value = value;
            this.scope = scope;
            this.outputComments = outputComments;
            this.waitFor = waitFor;
            this.filters = filters;
        }

        public ContainerRequestFilter getRequestFilter() {
            return null;
        }

        public ContainerResponseFilter getResponseFilter() {
            return this;
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

            AtmosphereResource<HttpServletRequest,HttpServletResponse> r =
                    (AtmosphereResource<HttpServletRequest,HttpServletResponse> ) servletReq
                        .getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);

            BroadcasterFactory bf = (BroadcasterFactory) servletReq
                    .getAttribute(AtmosphereServlet.BROADCASTER_FACTORY);

            boolean sessionSupported = (Boolean) servletReq.getAttribute
                    (AtmosphereServlet.SUPPORT_SESSION);

            if (action == Action.SUSPEND || action == Action.SUSPEND_RESUME) {
                boolean resumeOnBroadcast = (action == Action.SUSPEND_RESUME);
                Object o = response.getEntity();

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

                Broadcaster bc = (Broadcaster)servletReq.getAttribute(INJECTED_BROADCASTER);
                if (bc == null) {
                    bc = r.getBroadcaster();
                }

                if (sessionSupported && servletReq.getSession().getAttribute(SUSPENDED_RESOURCE) != null) {
                    AtmosphereResource<HttpServletRequest, HttpServletResponse> cached =
                            (AtmosphereResource) servletReq.getSession().getAttribute(SUSPENDED_RESOURCE);
                    bc = cached.getBroadcaster();
                    // Just in case something went wrong.
                    bc.removeAtmosphereResource(cached);
                }

                if (response.getEntity() instanceof Broadcastable) {
                    Broadcastable b = (Broadcastable) response.getEntity();
                    bc = b.b;
                    response.setEntity(b.message);

                    if ((scope == Suspend.SCOPE.REQUEST) && (bc.getScope() != Broadcaster.SCOPE.REQUEST)) {
                        bc.setScope(Broadcaster.SCOPE.REQUEST);
                    }
                } else if ((scope == Suspend.SCOPE.REQUEST) && (bc.getScope() != Broadcaster.SCOPE.REQUEST)) {
                    try {
                        String id = bc.getID();
                        bc.setID(bc.getClass().getSimpleName() + "-" + new Random().nextInt());

                        // Re-generate a new one with proper scope.
                        bc = bf.get();
                        bc.setScope(Broadcaster.SCOPE.REQUEST);
                        bc.setID(id);
                    } catch (InstantiationException ex) {
                        logger.log(Level.SEVERE, null, ex);
                    } catch (IllegalAccessException ex) {
                        logger.log(Level.SEVERE, null, ex);
                    }
                }
                configureFilter(bc);
                r.setBroadcaster(bc);

                if (sessionSupported) {
                    servletReq.getSession().setAttribute(SUSPENDED_RESOURCE, r);
                    servletReq.getSession().setAttribute(CONTAINER_RESPONSE, response);
                }

                servletReq.setAttribute(SUSPENDED_RESOURCE, r);
                servletReq.setAttribute(CONTAINER_RESPONSE, response);

                if (resumeOnBroadcast) {
                    servletReq.setAttribute(AtmosphereServlet.RESUME_ON_BROADCAST, new Boolean(true));
                }

                // Set the content-type based on the returned entity.
                try {                                           
                    MediaType contentType = response.getMediaType();
                    if (contentType == null && response.getEntity() != null) {
                        LinkedList<MediaType> l = new LinkedList<MediaType>();
                        l.add(request.getAcceptableMediaType(mediaTypes));
                        contentType = response.getMessageBodyWorkers().getMessageBodyWriterMediaType(
                                    response.getEntity().getClass(),
                                    response.getEntityType(),
                                    response.getAnnotations(),
                                   l);

                        if (contentType == null ||
                                contentType.isWildcardType() || contentType.isWildcardSubtype())
                            contentType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
                    }

                    r.getResponse().setContentType(contentType != null ?
                            contentType.toString() : "text/html;charset=ISO-8859-1");

                    r.suspend(value, outputComments && !resumeOnBroadcast);

                    if (response.getEntity() != null) {
                        response.write();
                    }
                } catch (IOException ex) {
                    throw new WebApplicationException(ex);
                }

            
                AtmosphereHandler a = (AtmosphereHandler) servletReq
                        .getAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER);

                for (Class<? extends AtmosphereResourceEventListener> e : listeners) {
                    try {
                        AtmosphereResourceEventListener el = e.newInstance();
                        if (r instanceof AtmosphereEventLifecycle) {
                            ((AtmosphereEventLifecycle) r).addEventListener(el);
                        }
                    } catch (Throwable t) {
                        throw new WebApplicationException(
                                new IllegalStateException("Invalid AtmosphereResourceEventListener " + e));
                    }
                }
            } else if (action == Action.RESUME) {
                
                if (response.getEntity() != null) {
                    try {
                        response.write();
                    } catch (IOException ex) {
                        throw new WebApplicationException(ex);
                    }
                }

                if (r == null && sessionSupported) {
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
            } else if (action == Action.BROADCAST || action == Action.RESUME_ON_BROADCAST) {
                AtmosphereResource ar = (AtmosphereResource) servletReq.getAttribute(SUSPENDED_RESOURCE);
                if (ar != null) {
                    r = ar;
                }
                broadcast(response, r, value);
            } else if (action == Action.SCHEDULE || action == Action.SCHEDULE_RESUME) {
                Object o = response.getEntity();
                Broadcaster b = r.getBroadcaster();
                if (response.getEntity() instanceof Broadcastable) {
                    b = ((Broadcastable) response.getEntity()).b;
                    o = ((Broadcastable) response.getEntity()).message;
                    response.setEntity(o);
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

                b.scheduleFixedBroadcast(o, waitFor, value, TimeUnit.SECONDS);

            }

            return response;
        }

        void configureResumeOnBroadcast(Broadcaster b) {
            Iterator<AtmosphereResource> i = b.getAtmosphereResources();
            while (i.hasNext()) {
                HttpServletRequest r = (HttpServletRequest) i.next().getRequest();
                r.setAttribute(AtmosphereServlet.RESUME_ON_BROADCAST, new Boolean(true));
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
            if (c.hasFilters()){
                return;
            }

            // Always the first one, before any transformation/filtering
            for (ClusterBroadcastFilter cbf : clusters) {
                cbf.setBroadcaster(bc);
                c.addFilter(cbf);
            }

            org.atmosphere.cpr.BroadcastFilter f = null;
            if (filters != null) {
                for (Class<org.atmosphere.cpr.BroadcastFilter> filter : filters) {
                    try {
                        f = filter.newInstance();
                    } catch (Throwable t) {
                        logger.warning("Invalid @BroadcastFilter: " + filter);
                    }
                    c.addFilter(f);
                }
            }
        }

        private void setListeners(Class<? extends AtmosphereResourceEventListener>[] listeners) {
            this.listeners = listeners;
        }

        void broadcast(ContainerResponse r, AtmosphereResource ar, int delay) {
            Object o = r.getEntity();

            Broadcaster b = ar.getBroadcaster();
            Object msg = o;
            // Something went wrong if null.
            if (o instanceof Broadcastable) {
                b = ((Broadcastable) o).b;
                msg = ((Broadcastable) o).message;
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
                            r.setEntity(t);
                        }
                    } else if (delay == 0) {
                        b.delayBroadcast(msg);
                    } else {
                        b.delayBroadcast(msg, delay, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ex) {
                    logger.log(Level.SEVERE, null, ex);
                } catch (ExecutionException ex) {
                    logger.log(Level.SEVERE, null, ex);
                }
            }
        }

        void addFilter(Broadcaster bc) {
            configureFilter(bc);
        }

        void resume(AtmosphereResource r) {
            AtmosphereHandler a = r.getAtmosphereConfig().getAtmosphereHandler(r.getBroadcaster());

            r.resume();
        }

        void addCluster(ClusterBroadcastFilter f) {
            clusters.add(f);
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

        if (logger.isLoggable(Level.FINE)) {
            for (Annotation a : am.getAnnotations()) {
                logger.log(Level.FINE, "AtmosphereFilter processing annotation: " + a);
            }
        }

        if (am.isAnnotationPresent(Broadcast.class)) {

            Filter f;
            int delay = am.getAnnotation(Broadcast.class).delay();
            Class[] value = am.getAnnotation(Broadcast.class).value();

            if (am.getAnnotation(Broadcast.class).resumeOnBroadcast()) {
                f = new Filter(Action.RESUME_ON_BROADCAST, delay, 0, Suspend.SCOPE.APPLICATION, true, value);
            } else {
                f = new Filter(Action.BROADCAST, delay, 0, Suspend.SCOPE.APPLICATION, true, value);
            }

            list.addLast((ResourceFilter) f);

            if (am.isAnnotationPresent(Cluster.class)) {
                value = am.getAnnotation(Cluster.class).value();
                for (Class<ClusterBroadcastFilter> c : value) {
                    try {
                        ClusterBroadcastFilter cbf = c.newInstance();
                        cbf.setClusterName(am.getAnnotation(Cluster.class).name());
                        f.addCluster(cbf);
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "Invalid ClusterBroadcastFilter", t);
                    }
                }
            }
        }

        if (am.isAnnotationPresent(Suspend.class)) {
                      
            int value = am.getAnnotation(Suspend.class).period();
            Suspend.SCOPE scope = am.getAnnotation(Suspend.class).scope();
            boolean outputComments = am.getAnnotation(Suspend.class).outputComments();

            Filter f;
            if (am.getAnnotation(Suspend.class).resumeOnBroadcast()) {
                f = new Filter(Action.SUSPEND_RESUME, value, 0, scope, outputComments);
            } else {
                f = new Filter(Action.SUSPEND, value, 0, scope,outputComments);
            }
            f.setListeners(am.getAnnotation(Suspend.class).listeners());

            list.addFirst((ResourceFilter) f);
        }

        if (am.isAnnotationPresent(Resume.class)) {
            int value = am.getAnnotation(Resume.class).value();
            list.addFirst((ResourceFilter)
                    new Filter(Action.RESUME, value));
        }

        if (am.isAnnotationPresent(Schedule.class)) {
            int period = am.getAnnotation(Schedule.class).period();
            int waitFor = am.getAnnotation(Schedule.class).waitFor();

            if (am.getAnnotation(Schedule.class).resumeOnBroadcast()) {
                list.addFirst((ResourceFilter)
                        new Filter(Action.SCHEDULE_RESUME, period, waitFor));
            } else {
                list.addFirst((ResourceFilter)
                        new Filter(Action.SCHEDULE, period, waitFor));
            }
        }

        // Nothing, normal Jersey application.
        return list.size() > 0 ? list : null;
    }

}
