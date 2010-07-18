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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.AtmosphereServlet.ATMOSPHERE_RESOURCE;

/**
 * A {@link Meteor} is a simple class that can be used from a {@link javax.servlet.Servlet}
 * to suspend, broadcast and resume a response. A {@link Meteor} can be created by invoking
 * the build() method.
 * <p><code>
 * Meteor.build(HttpServletRequest).suspend(-1);
 * </code></p><p>
 * A Meteor is usually created when an application need to suspend a response.
 * A Meteor instance can then be cached and re-used later for either
 * broadcasting a message, or when an application needs to resume the
 * suspended response.
 *
 * @author Jeanfrancois Arcand
 */
public class Meteor {

    private final static ConcurrentHashMap<HttpServletRequest, Meteor> cache =
            new ConcurrentHashMap<HttpServletRequest, Meteor>();

    private final AtmosphereResource<HttpServletRequest, HttpServletResponse> r;

    private Object o;

    private Meteor(AtmosphereResource<HttpServletRequest, HttpServletResponse> r,
                   List<BroadcastFilter> l, Serializer s) {

        this.r = r;
        r.setSerializer(s);
        if (l != null) {
            for (BroadcastFilter f : l) {
                r.getBroadcaster().getBroadcasterConfig().addFilter(f);
            }
        }
        cache.put(r.getRequest(), this);
    }

    /**
     * Retrieve an instance of {@link Meteor} based on the {@link HttpServletRequest}
     *
     * @param r {@link HttpServletRequest}
     * @return a {@link Meteor} or null if not found
     */
    public static Meteor lookup(HttpServletRequest r) {
        return cache.get(r);
    }


    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest}
     *
     * @param r an {@link HttpServletRequest}
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest r) {

        Meteor m = lookup(r);
        if (m != null) return m;

        return build(r, null);
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest} and use the
     * {@link Serializer} for writting the result of a broadcast operation using
     * the {@link HttpServletResponse}
     *
     * @param r an {@link HttpServletRequest}
     * @param s a {@link Serializer} used when writing broadcast events.
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest r, Serializer s) {
        return build(r, null, s);
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest} and use a list of
     * {@link BroadcastFilter} and {@link Serializer} for writting the result
     * of a broadcast operation the {@link HttpServletResponse}.
     *
     * @param req an {@link HttpServletRequest}
     * @param l   a list of {@link BroadcastFilter}
     * @param s   a {@link Serializer} used when writing broadcast events.
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest req, List<BroadcastFilter> l, Serializer s) {
        return build(req, Broadcaster.SCOPE.APPLICATION, l, s);
    }

    /**
     * Create a {@link Meteor} using the {@link HttpServletRequest} and use a list of
     * {@link BroadcastFilter} and {@link Serializer} for writting the result
     * of a broadcast operation the {@link HttpServletResponse}.
     *
     * @param req   an {@link HttpServletRequest}
     * @param scope the {@link Broadcaster.SCOPE}}
     * @param l     a list of {@link BroadcastFilter}
     * @param s     a {@link Serializer} used when writing broadcast events.
     * @return a {@link Meteor} than can be used to resume, suspend and broadcast {@link Object}
     */
    public final static Meteor build(HttpServletRequest req, Broadcaster.SCOPE scope,
                                     List<BroadcastFilter> l, Serializer s) {
        AtmosphereResource<HttpServletRequest, HttpServletResponse> r =
                (AtmosphereResource<HttpServletRequest, HttpServletResponse>)
                        req.getAttribute(ATMOSPHERE_RESOURCE);
        if (r == null) throw new IllegalStateException("MeteorServlet not defined in web.xml");

        Broadcaster b = null;

        if (scope == Broadcaster.SCOPE.REQUEST) {
            try {
                b = BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, DefaultBroadcaster.class.getSimpleName() + UUID.randomUUID());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            b.setScope(scope);
            r.setBroadcaster(b);
        }

        Meteor m = new Meteor(r, l, s);
        return m;
    }

    /**
     * Suspend the underlying {@link HttpServletResponse}. Passing value of -1
     * suspend the response forever.
     *
     * @param l the maximum time a response stay suspended.
     * @return {@link Meteor}
     */
    public Meteor suspend(long l) {
        r.suspend(l);
        return this;
    }

    /**
     * Suspend the underlying {@link HttpServletResponse}. Passing value of -1
     * suspend the response forever.
     *
     * @param l the maximum time a response stay suspended.
     * @param outputComments the maximum time a response stay suspended.
     * @return {@link Meteor}
     */
    public Meteor suspend(long l, boolean outputComments) {
        r.suspend(l, outputComments);
        return this;
    }

    /**
     * Resume the underlying {@link HttpServletResponse}
     *
     * @return {@link Meteor}
     */
    public Meteor resume() {
        r.resume();
        cache.remove(r.getRequest());
        return this;
    }

    /**
     * Broadcast an {@link Object} to all suspended response.
     *
     * @param o an {@link Object}
     * @return {@link Meteor}
     */
    public Meteor broadcast(Object o) {
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
        r.getBroadcaster().delayBroadcast(o, period, TimeUnit.SECONDS);
        return this;
    }

    /**
     * Return the underlying {@link Broadcaster}
     *
     * @return
     */
    public Broadcaster getBroadcaster() {
        return r.getBroadcaster();
    }

    /**
     * Set a {@link Broadcaster} instance.
     *
     * @param b
     */
    public void setBroadcaster(Broadcaster b) {
        r.setBroadcaster(b);
    }

    /**
     * Return an {@link Object} with this {@link Meteor}
     *
     * @return the {@link Object}
     */
    public Object attachement() {
        return o;
    }

    /**
     * Attach an {@link Object} with this {@link Meteor}
     *
     * @return the {@link Object}
     */
    public void attach(Object o) {
        this.o = o;
    }

    /**
     * Add a {@link AtmosphereResourceEventListener} which gets invoked when
     * response are resuming, when the remote client close the connection or
     * when the a {@link Broadcaster#broadcast} operations occurs.
     *
     * @param e an inatance of {@link AtmosphereResourceEventListener}
     */
    public void addListener(AtmosphereResourceEventListener e) {
        if (r instanceof AtmosphereEventLifecycle) {
            ((AtmosphereEventLifecycle) r).addEventListener(e);
        }
    }

    /**
     * Remove a {@link AtmosphereResourceEventListener} which gets invoked when
     * response are resuming, when the remote client close the connection or
     * when the a {@link Broadcaster#broadcast} operations occurs.
     *
     * @param e an inatance of {@link AtmosphereResourceEventListener}
     */
    public void removeListener(AtmosphereResourceEventListener e) {
        if (r instanceof AtmosphereEventLifecycle) {
            ((AtmosphereEventLifecycle) r).removeEventListener(e);
        }
    }
    
    static void destroy() {
        cache.clear();
    }
}
