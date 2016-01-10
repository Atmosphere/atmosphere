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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.AtmosphereResourceImpl.METEOR;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_RESOURCE;

/**
 * A {@link Meteor} is a simple class that can be used from a {@link javax.servlet.Servlet}
 * to suspend, broadcast and resume responses. A {@link Meteor} can be created by invoking
 * the build() method.
 * <p><code>
 * Meteor.build(HttpServletRequest).suspend(-1);
 * </code></p><p>
 * A Meteor is usually created when an application needs to suspend a response.
 * A Meteor instance can then be cached and re-used later for either
 * broadcasting a message, or when an application needs to resume the
 * suspended response.
 *
 * @author Jeanfrancois Arcand
 */
public class Meteor {

    private static final Logger logger = LoggerFactory.getLogger(Meteor.class);

    private final AtmosphereResource r;
    private Object o;
    private AtomicBoolean isDestroyed = new AtomicBoolean(false);

    private Meteor(AtmosphereResource r,
                   List<BroadcastFilter> l, Serializer s) {

        this.r = r;
        this.r.setSerializer(s);
        if (l != null) {
            for (BroadcastFilter f : l) {
                this.r.getBroadcaster().getBroadcasterConfig().addFilter(f);
            }
        }
    }

    /**
     * Retrieve an instance of {@link Meteor} based on the {@link HttpServletRequest}.
     *
     * @param r {@link HttpServletRequest}
     * @return a {@link Meteor} or null if not found
     */
    public static Meteor lookup(HttpServletRequest r) {
        Object o = r.getAttribute(METEOR);
        return o == null ? null : Meteor.class.isAssignableFrom(o.getClass()) ? Meteor.class.cast(o) : null;
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest}.
     *
     * @param r an {@link HttpServletRequest}
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest r) {
        return build(r, null);
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest} and use the
     * {@link Serializer} for writing the result of a broadcast operation using
     * the {@link HttpServletResponse}.
     *
     * @param r an {@link HttpServletRequest}
     * @param s a {@link Serializer} used when writing broadcast events
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest r, Serializer s) {
        return build(r, null, s);
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest} and use a list of
     * {@link BroadcastFilter} and {@link Serializer} for writing the result
     * of a broadcast operation the {@link HttpServletResponse}.
     *
     * @param req an {@link HttpServletRequest}
     * @param l   a list of {@link BroadcastFilter}
     * @param s   a {@link Serializer} used when writing broadcast events
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest req, List<BroadcastFilter> l, Serializer s) {
        return build(req, Broadcaster.SCOPE.APPLICATION, l, s);
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest} and use a list of
     * {@link BroadcastFilter} and {@link Serializer} for writing the result
     * of a broadcast operation the {@link HttpServletResponse}.
     *
     * @param req   an {@link HttpServletRequest}
     * @param scope the {@link Broadcaster.SCOPE}}
     * @param l     a list of {@link BroadcastFilter}
     * @param s     a {@link Serializer} used when writing broadcast events
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest req, Broadcaster.SCOPE scope,
                                     List<BroadcastFilter> l, Serializer s) {
        AtmosphereResource r =
                (AtmosphereResource)
                        req.getAttribute(ATMOSPHERE_RESOURCE);
        if (r == null) throw new IllegalStateException("MeteorServlet not defined in web.xml");

        Broadcaster b = null;
        if (scope == Broadcaster.SCOPE.REQUEST) {
            try {
                BroadcasterFactory f = r.getAtmosphereConfig().getBroadcasterFactory();
                b = f.get(DefaultBroadcaster.class, DefaultBroadcaster.class.getSimpleName()
                        + r.getAtmosphereConfig().uuidProvider().generateUuid());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            b.setScope(scope);
            r.setBroadcaster(b);
            req.setAttribute(AtmosphereResourceImpl.SKIP_BROADCASTER_CREATION, Boolean.TRUE);
        }

        Meteor m = new Meteor(r, l, (s != null ? s : r.getSerializer()));
        req.setAttribute(METEOR, m);
        return m;
    }

    /**
     * Suspend the underlying {@link HttpServletResponse}. Passing a value of -1
     * suspends the response forever.
     *
     * @param l the maximum time a response stay suspended
     * @return {@link Meteor}
     */
    public Meteor suspend(long l) {
        if (destroyed()) return null;
        r.suspend(l);
        return this;
    }

    /**
     * Resume the Meteor after the first broadcast operation. This is useful when long-polling is used.
     *
     * @param resumeOnBroadcast
     * @return this
     */
    public Meteor resumeOnBroadcast(boolean resumeOnBroadcast) {
        r.resumeOnBroadcast(resumeOnBroadcast);
        return this;
    }

    /**
     * Return the current {@link org.atmosphere.cpr.AtmosphereResource.TRANSPORT}. The transport needs to be
     * explicitly set by the client by adding the appropriate {@link HeaderConfig#X_ATMOSPHERE_TRANSPORT} value,
     * which can be long-polling, streaming, websocket or JSONP.
     *
     * @return
     */
    public AtmosphereResource.TRANSPORT transport() {
        return r.transport();
    }

    /**
     * Suspend the underlying {@link HttpServletResponse}. Passing a value of -1
     * suspends the response forever.
     *
     * @param timeout  the maximum time a response stay suspended
     * @param timeunit The time unit of the timeout value
     * @return {@link Meteor}
     */

    public Meteor suspend(long timeout, TimeUnit timeunit) {
        if (destroyed()) return null;
        r.suspend(timeout, timeunit);
        return this;
    }

    /**
     * Resume the underlying {@link HttpServletResponse}.
     *
     * @return {@link Meteor}
     */
    public Meteor resume() {
        if (destroyed()) return null;
        r.resume();
        return this;
    }

    /**
     * Broadcast an {@link Object} to all suspended responses.
     *
     * @param o an {@link Object}
     * @return {@link Meteor}
     */
    public Meteor broadcast(Object o) {
        if (destroyed()) return null;
        r.getBroadcaster().broadcast(o);
        return this;
    }

    /**
     * Schedule a periodic broadcast, in seconds.
     *
     * @param o      an {@link Object}
     * @param period period in seconds
     * @return {@link Meteor}
     */
    public Meteor schedule(Object o, long period) {
        if (destroyed()) return null;
        r.getBroadcaster().scheduleFixedBroadcast(o, period, TimeUnit.SECONDS);
        return this;
    }

    /**
     * Schedule a delayed broadcast, in seconds.
     *
     * @param o      an {@link Object}
     * @param period period in seconds
     * @return {@link Meteor}
     */
    public Meteor delayBroadadcast(Object o, long period) {
        if (destroyed()) return null;
        r.getBroadcaster().delayBroadcast(o, period, TimeUnit.SECONDS);
        return this;
    }

    /**
     * Return the underlying {@link Broadcaster}.
     *
     * @return
     */
    public Broadcaster getBroadcaster() {
        if (destroyed()) return null;
        return r.getBroadcaster();
    }

    /**
     * Set a {@link Broadcaster} instance.
     *
     * @param b
     */
    public void setBroadcaster(Broadcaster b) {
        if (destroyed()) return;
        r.setBroadcaster(b);
    }

    /**
     * Return an {@link Object} with this {@link Meteor}.
     *
     * @return the {@link Object}
     */
    public Object attachement() {
        return o;
    }

    /**
     * Attach an {@link Object} with this {@link Meteor}.
     *
     * @return the {@link Object}
     */
    public void attach(Object o) {
        this.o = o;
    }

    /**
     * Add a {@link AtmosphereResourceEventListener} which gets invoked when
     * responses are resuming, when the remote client closes the connection or
     * when the a {@link Broadcaster#broadcast} operations occurs.
     *
     * @param e an instance of {@link AtmosphereResourceEventListener}
     */
    public Meteor addListener(AtmosphereResourceEventListener e) {
        if (!destroyed()) {
            r.addEventListener(e);
        }
        return this;
    }

    /**
     * Remove a {@link AtmosphereResourceEventListener} which gets invoked when
     * a response is resuming, when the remote client closes the connection or
     * when the a {@link Broadcaster#broadcast} operations occurs.
     *
     * @param e an instance of {@link AtmosphereResourceEventListener}
     */
    public Meteor removeListener(AtmosphereResourceEventListener e) {
        if (!destroyed()) {
            r.removeEventListener(e);
        }
        return this;
    }

    /**
     * Mark this instance as destroyed. No more operations will be allowed.
     */
    public void destroy() {
        isDestroyed.set(true);
    }

    private boolean destroyed() {
        if (isDestroyed.get()) {
            logger.debug("This Meteor is destroyed and cannot be used.");
            return true;
        }
        return false;
    }

    /**
     * Return the underlying {@link AtmosphereResource}.
     *
     * @return the underlying {@link AtmosphereResource}
     */
    public AtmosphereResource getAtmosphereResource() {
        return r;
    }

    /**
     * Return the {@link org.atmosphere.cpr.AtmosphereConfig}
     * @return the {@link org.atmosphere.cpr.AtmosphereConfig}
     */
    public AtmosphereConfig getAtmosphereConfig() {
        return r.getAtmosphereConfig();
    }
}
