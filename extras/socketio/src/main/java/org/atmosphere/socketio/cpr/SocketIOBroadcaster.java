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
package org.atmosphere.socketio.cpr;

import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.socketio.transport.SocketIOPacketImpl;

import javax.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * SocketIO specific Broadcaster. This broadcaster#broadcast method always exclude
 *
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
@BroadcasterService
public class SocketIOBroadcaster extends DefaultBroadcaster {

    public SocketIOBroadcaster(String id, AtmosphereConfig config) {
        super(id, config);
    }

    /**
     * Broadcast to all EXCEPT the passed AtmosphereResource
     */
    @Override
    public <T> Future<T> broadcast(T m, AtmosphereResource resource) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }

        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();
        Set<AtmosphereResource> sub = new HashSet<AtmosphereResource>();
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
     * Broadcast to all EXCEPT the passed AtmosphereResource
     */
    public <T> Future<T> broadcast(T m, Set<AtmosphereResource> subset) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }
        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();

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
     * Broadcast to all EXCEPT the passed AtmosphereResource
     */
    public <T> Future<T> broadcast(T m, List<HttpSession> sessions) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }

        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();
        Set<AtmosphereResource> subset = new HashSet<AtmosphereResource>();
        subset.addAll(resources);
        for (AtmosphereResource r : resources) {
            if (!r.getAtmosphereResourceEvent().isCancelled() &&
                    sessions.contains((r.getRequest()).getSession())) {
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
     * Broadcast to all EXCEPT the passed HttpSession
     */
    public <T> Future<T> broadcast(T m, HttpSession s) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }

        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();
        Set<AtmosphereResource> subset = new HashSet<AtmosphereResource>();
        subset.addAll(resources);

        for (AtmosphereResource r : resources) {
            if (!r.getAtmosphereResourceEvent().isCancelled() &&
                    s.equals((r.getRequest()).getSession())) {
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