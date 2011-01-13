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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.Future;


/**
 * Abstract {@link org.atmosphere.cpr.Broadcaster} that delegates the internal processing to a proxy.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class AbstractBroadcasterProxy extends DefaultBroadcaster {
    private static final Logger logger = LoggerFactory.getLogger(AbstractBroadcasterProxy.class);

    private Method jerseyBroadcast;
    protected AtmosphereServlet.AtmosphereConfig config;

    public AbstractBroadcasterProxy() {
        this(AbstractBroadcasterProxy.class.getSimpleName());
    }

    public AbstractBroadcasterProxy(String id) {
        super(id);
        start();
    }

    /**
     * Allow this Broadcaster to configure itself using the {@link AtmosphereServlet.AtmosphereConfig} or the 
     * {@link javax.servlet.ServletContext}.
     *
     * @param config the {@link AtmosphereServlet.AtmosphereConfig}
     */
    public void configure(AtmosphereServlet.AtmosphereConfig config) {
        this.config = config;
    }

    /**
     * Implement this method to broadcast message received from an external source like JGroups, Redis, etc.
     */
    abstract public void incomingBroadcast();

    /**
     * Implement this method to broadcast message to external source like JGroups, Redis, etc.
     * @param message outgoing message
     */
    abstract public void outgoingBroadcast(Object message);

    /**
     * {@inheritDoc}
     */
    @Override
    protected Runnable getBroadcastHandler() {
        return new Runnable() {
            public void run() {
                incomingBroadcast();
            }
        };
    }

    protected void reconfigure() {
        if (notifierFuture != null) {
            notifierFuture.cancel(true);
        }
        notifierFuture = bc.getExecutorService().submit(getBroadcastHandler());
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected void broadcast(final AtmosphereResource<?, ?> r, final AtmosphereResourceEvent e) {
        if (r.getRequest() instanceof HttpServletRequest) {
            if (((HttpServletRequest) r.getRequest()).getAttribute(AtmosphereServlet.CONTAINER_RESPONSE) != null) {
                try {
                    if (jerseyBroadcast == null) {
                        Class jerseyBroadcasterUtil = Class.forName("org.atmosphere.jersey.util.JerseyBroadcasterUtil");
                        jerseyBroadcast = jerseyBroadcasterUtil.getMethod("broadcast", new Class[]{AtmosphereResource.class, AtmosphereResourceEvent.class});
                    }
                    jerseyBroadcast.invoke(null, new Object[]{r, e});
                } catch (Throwable t) {
                    super.broadcast(r, e);
                }
            } else {
                super.broadcast(r, e);
            }
        }
    }

    protected void broadcastReceivedMessage(Object message) {
        try {
            Object newMsg = filter(message);
            push(new Entry(newMsg, null, new BroadcasterFuture<Object>(newMsg), message));
        } catch (Throwable t) {
            logger.error("failed to push message: " + message, t);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg) {
        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        try {
            outgoingBroadcast(msg);
            push(new Entry(newMsg, null, f, false));
        } finally {
            f.done();
        }
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, AtmosphereResource<?, ?> r) {
        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        try {
            outgoingBroadcast(msg);
            push(new Entry(newMsg, r, f, false));
        } finally {
            f.done();
        }
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, Set<AtmosphereResource<?, ?>> subset) {
        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        try {
            outgoingBroadcast(msg);
            push(new Entry(newMsg, subset, f, false));
        } finally {
            f.done();
        }
        return f;
    }
}
