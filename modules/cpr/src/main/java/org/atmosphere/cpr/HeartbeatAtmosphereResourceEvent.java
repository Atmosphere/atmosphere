/*
 * Copyright 2008-2020 Async-IO.org
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

package org.atmosphere.cpr;

import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;

/**
 * <p>
 * Specifies to the observable that {@link org.atmosphere.cpr.AtmosphereResourceEventListener#onHeartbeat(org.atmosphere.cpr.AtmosphereResourceEvent)}
 * should be invoked when it fires event to observers.
 * </p>
 *
 * @version 1.0
 * @author Guillaume DROUET
 * @since 2.2
 */
public class HeartbeatAtmosphereResourceEvent extends AtmosphereResourceEventImpl {

    /**
     * <p>
     * Builds a new event.
     * </p>
     *
     * @param resource the resource
     */
    public HeartbeatAtmosphereResourceEvent(final AtmosphereResourceImpl resource) {
        super(resource);
    }
}
