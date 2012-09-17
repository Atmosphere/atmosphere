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
package org.atmosphere.samples.chat;

import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.socketio.transport.SocketIOPacketImpl;
import org.atmosphere.util.ExcludeSessionBroadcaster;

import java.util.Set;
import java.util.concurrent.Future;

@BroadcasterService
public class SocketIOBroadcaster extends ExcludeSessionBroadcaster {

    public SocketIOBroadcaster(String id, AtmosphereConfig config) {
        super(id, config);
    }

    @Override
    public Future<Object> broadcast(Object m) {
        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();
        return super.broadcast(msg);
    }

    @Override
    public Future<Object> broadcast(Object m, AtmosphereResource resource) {
        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();
        return super.broadcast(msg, resource);
    }

    @Override
    public Future<Object> broadcast(Object m, Set<AtmosphereResource> subset) {
        Object msg = new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, m.toString()).toString();
        return super.broadcast(msg, subset);
    }

}