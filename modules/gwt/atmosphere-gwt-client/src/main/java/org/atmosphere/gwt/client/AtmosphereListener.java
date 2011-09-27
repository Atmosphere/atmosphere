/*
* Copyright 2011 Jeanfrancois Arcand
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

import java.io.Serializable;
import java.util.List;

/**
 * Listens for events from a {@link CometClient}.
 *
 * @author Richard Zschech
 */
public interface AtmosphereListener {

    /**
     * The connection has been established
     *
     * @param heartbeat
     */
    public void onConnected(int heartbeat, int connectionID);

    /**
     * Send just before the connection is stopped
     * (this can happen also because the window is being closed)
     */
    public void onBeforeDisconnected();

    /**
     * The connection has disconnected and is being refreshed
     */
    public void onDisconnected();

    /**
     * A Comet error has occurred
     *
     * @param exception
     * @param connected
     */
    public void onError(Throwable exception, boolean connected);

    /**
     * The connection has received a heartbeat
     */
    public void onHeartbeat();

    /**
     * The connection should be refreshed by the client
     */
    public void onRefresh();

    /**
     * A batch of messages has been received
     *
     * @param messages
     */
    public void onMessage(List<? extends Serializable> messages);
}
