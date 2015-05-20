/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereResource;

/**
 * A factory for retrieving {@link WebSocket}
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketFactory {

    /**
     * Retrieve the {@link WebSocket} associated with a uuid. The uuid could be the one returned by
     * the {@link AtmosphereResource#uuid()} or an application generated one.
     *
     * @param uuid a UUID associated
     * @return
     */
    WebSocket find(String uuid);

}
