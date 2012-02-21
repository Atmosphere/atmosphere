/*
 * Copyright 2012 Sebastien Dionne
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
package org.atmosphere.protocol.socketio.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ceci est pour debugger.  S'il n'y a pas de modifications, on prendra
 * celui dans le module runtime
 * 
 * Ceci est dans le bug de debugger le broadcast et le cache lors des connections
 * long-polling
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
public class SocketIOExcludeSessionBroadcaster extends DefaultBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SocketIOExcludeSessionBroadcaster.class);

    public SocketIOExcludeSessionBroadcaster(String id, AtmosphereConfig config) {
        super(id, config);
    }

    /**
     * the AtmosphereResource r will be exclude for this broadcast
     *
     * @param msg
     * @param r
     * @param <T>
     * @return
     */
    @Override 
    public <T> Future<T> broadcast(T msg, AtmosphereResource<?, ?> resource) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }
        
        logger.info("To broadcast from : " + ((HttpServletRequest) resource.getRequest()).getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID) + " message to broadcast : " + msg);

        Set<AtmosphereResource<?, ?>> sub = new HashSet<AtmosphereResource<?, ?>>();
        sub.addAll(resources);
        sub.remove(resource);
        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return null;
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, sub, f, msg));
        return f;
    }


    /**
     * the AtmosphereResources subset will be exclude for this broadcast
     *
     * @param msg
     * @param subset
     * @param <T>
     * @return
     */
    @Override
    public <T> Future<T> broadcast(T msg, Set<AtmosphereResource<?, ?>> subset) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }
        
        subset.retainAll(resources);
        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return null;
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * a list of sessions will be exclude for this broadcast
     *
     * @param msg
     * @param sessions
     * @param <T>
     * @return
     */
    public <T> Future<T> broadcast(T msg, List<HttpSession> sessions) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }
        Set<AtmosphereResource<?, ?>> subset = new HashSet<AtmosphereResource<?, ?>>();
        subset.addAll(resources);
        for (AtmosphereResource<?, ?> r : resources) {
            if (!r.getAtmosphereResourceEvent().isCancelled() &&
                    sessions.contains(((HttpServletRequest) r.getRequest()).getSession())) {
                subset.remove(r);
            }
        }
        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return null;
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * session will be exclude for this broadcast
     *
     * @param msg
     * @param s
     * @param <T>
     * @return
     */
    public <T> Future<T> broadcast(T msg, HttpSession s) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }

        Set<AtmosphereResource<?, ?>> subset = new HashSet<AtmosphereResource<?, ?>>();
        subset.addAll(resources);

        for (AtmosphereResource<?, ?> r : resources) {
            if (!r.getAtmosphereResourceEvent().isCancelled() &&
                    s.equals(((HttpServletRequest) r.getRequest()).getSession())) {
                subset.remove(r);
            }
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return null;
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, subset, f, msg));
        return f;
    }
}