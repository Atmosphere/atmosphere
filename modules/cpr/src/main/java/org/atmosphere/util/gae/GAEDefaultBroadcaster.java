/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common ~Development
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
package org.atmosphere.util.gae;


import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link Broadcaster} implementation.
 * <p/>
 * This Broadcaster uses the calling thread to execute it's {@link Broadcaster#broadcast}
 * operations.
 *
 * @author Jeanfrancois Arcand
 */
public class GAEDefaultBroadcaster extends DefaultBroadcaster {

    public GAEDefaultBroadcaster() {
        super();
        bc = new GAEBroadcasterConfig(AtmosphereServlet.broadcasterFilters);
    }

    public GAEDefaultBroadcaster(String name) {
        super(name);
        bc = new GAEBroadcasterConfig(AtmosphereServlet.broadcasterFilters);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg) {
        Object newMsg = filter(msg);
        if (msg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        f.done();
        push(new Entry(newMsg, null, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, AtmosphereResource<?,?> r) {
        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        f.done();
        push(new Entry(newMsg, r, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, Set<AtmosphereResource<?,?>> subset) {
        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        f.done();
        push(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> delayBroadcast(final T o, long delay, TimeUnit t) {
        throw new UnsupportedOperationException(GAEBroadcasterConfig.NOT_SUPPORTED);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<?> scheduleFixedBroadcast(final Object o, long period, TimeUnit t) {
        throw new UnsupportedOperationException(GAEBroadcasterConfig.NOT_SUPPORTED);
    }
}
