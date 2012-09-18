/*
* Copyright 2012 Jeanfrancois Arcand
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
/*
 * Copyright 2009 Richard Zschech.
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
package org.atmosphere.gwt.client;

import java.util.List;

/**
 * Listens for events from an {@link AtmosphereClient}.
 *
 * @author Richard Zschech
 * @author Pierre Havelaar
 */
public interface AtmosphereListener {

    /**
     * The connection has been established
     *
     * @param heartbeat This is the interval with which the server will send heartbeats
     * @param connectionID This is the unique number that identifies this connection
     */
    public void onConnected(int heartbeat, int connectionID);

    /**
     * Send just before the connection is stopped
     * (this can happen also because the window is being closed)
     */
    public void onBeforeDisconnected();

    /**
     * The connection has disconnected. When the disconnect was unexpected ({@link AtmosphereClient#isRunning()} == true)
     * the connecting will be refreshed after this and you can expect the next event to be {@link #onAfterRefresh() }
     */
    public void onDisconnected();

    /**
     * An error has occurred.
     *
     * @param exception
     * @param connected This will indicate whether the connection is still alive
     */
    public void onError(Throwable exception, boolean connected);

    /**
     * The connection has received a heartbeat. When a heartbeat is not received at the expected time, the
     * connection is assumed to be dead and a new one is established.
     */
    public void onHeartbeat();

    /**
     * The connection will be refreshed by the client. This will occur to prevent data from accumulating in 
     * the clients comet implementation and slowing down message processing. Normally you don't need to do
     * anything with this event. It signals the start of a scheduled reconnection. A new connection will be
     * established and the current connection is only dropped when the new connection has become alive.
     * 
     */
    public void onRefresh();
    
    /**
     * You will receive this after a connection has been refreshed or re-established and is ready to process
     * new events.
     */
    public void onAfterRefresh();

    /**
     * A batch of messages from the server has arrived.
     *
     * @param messages
     */
    public void onMessage(List<?> messages);
}
