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
package org.atmosphere.jersey.util;


import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.util.SimpleBroadcaster;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation that use the calling thread when broadcasting events.
 *
 * @author Jeanfrancois Arcand
 */
public class JerseySimpleBroadcaster extends SimpleBroadcaster {

    @Override
    protected void invokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        JerseyBroadcasterUtil.broadcast(r, e, this);
    }
}