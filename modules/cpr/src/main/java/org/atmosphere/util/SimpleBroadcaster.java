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


import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Future;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation that use the calling thread when broadcasting events.
 *
 * @author Jeanfrancois Arcand
 */
public class SimpleBroadcaster extends DefaultBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SimpleBroadcaster.class);

    public SimpleBroadcaster(String id, AtmosphereConfig config) {
        super(id, config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected BroadcasterConfig createBroadcasterConfig(AtmosphereConfig config){
        BroadcasterConfig bc = (BroadcasterConfig) config.properties().get(BroadcasterConfig.class.getName());
        if (bc == null) {
            bc = new BroadcasterConfig(AtmosphereServlet.broadcasterFilters, config, false);
            config.properties().put(BroadcasterConfig.class.getName(), bc);
        }
        return bc;
    }

    protected void start() {
        if (!started.getAndSet(true)) {
            setID(name);
            broadcasterCache = bc.getBroadcasterCache();
            broadcasterCache.start();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBroadcasterConfig(BroadcasterConfig bc) {
        this.bc = bc;
        bc.setExecutorService(null, false).setAsyncWriteService(null, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg) {

        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();

        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        f.done();
        push(new Entry(newMsg, null, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, AtmosphereResource<?, ?> r) {

        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();

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
    public <T> Future<T> broadcast(T msg, Set<AtmosphereResource<?, ?>> subset) {

        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();

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
    protected void queueWriteIO(AtmosphereResource<?, ?> r, Object finalMsg, Entry entry) throws InterruptedException {
        synchronized (r) {
            executeAsyncWrite(new AsyncWriteToken(r, finalMsg, entry.future, entry.originalMessage));
        }
    }
}