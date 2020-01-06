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

package org.atmosphere.jersey;

import com.sun.jersey.spi.container.ContainerResponse;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.jersey.util.JerseyBroadcasterUtil;

/**
 * Special {@link Broadcaster} that use the {@link ContainerResponse} under the hood
 * to serialize the response.
 *
 * @author Jeanfrancois Arcand
 */
public class JerseyBroadcaster extends DefaultBroadcaster {

    public JerseyBroadcaster() {}

    @Override
    protected void invokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        JerseyBroadcasterUtil.broadcast(r, e, this);
    }

}
