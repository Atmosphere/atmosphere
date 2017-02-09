/*
 * Copyright 2017 Async-IO.org
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

package org.atmosphere.runtime;

/**
 * Receive notifications when resume, client disconnect or broadcast events occur. Also extends
 * {@link AtmosphereResourceHeartbeatEventListener} which is notified when heartbeat events occur.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResourceEventListener extends AtmosphereResourceHeartbeatEventListener {

    /**
     * Invoked when the {@link AtmosphereResource#suspend} is in the process of being suspended
     * but nothing has yet been written on the connection. An implementation could configure the request's headers,
     * flush some data, etc. during that stage.
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onPreSuspend(AtmosphereResourceEvent event);

    /**
     * Invoked when the {@link AtmosphereResource#suspend} has been completed and the response is
     * considered as suspended.
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onSuspend(AtmosphereResourceEvent event);

    /**
     * Invoked when the {@link AtmosphereResource#resume} is invoked or when the
     * suspend's time out expires.
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onResume(AtmosphereResourceEvent event);

    /**
     * Invoked when the remote connection gets closed.
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onDisconnect(AtmosphereResourceEvent event);

    /**
     * Invoked when a {@link Broadcaster#broadcast} occurs.
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onBroadcast(AtmosphereResourceEvent event);

    /**
     * Invoked when an operations failed to execute for an unknown reason (eg. IOException because the client
     * remotely closed the connection, a broken connection, etc.).
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onThrowable(AtmosphereResourceEvent event);

    /**
     * Invoked when {@link AtmosphereResource#close} gets called.
     *
     * @param event a {@link org.atmosphere.runtime.AtmosphereResourceEvent}
     */
    void onClose(AtmosphereResourceEvent event);
}
