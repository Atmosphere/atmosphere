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
package org.atmosphere.jersey.tests;

import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerBase;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.jersey.Broadcastable;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.util.UUID;

/**
 * Concurrent Resource Test case.
 *
 * @author Jeanfrancois Arcand
 */
@Path("/concurrent")
@Produces("text/plain;charset=ISO-8859-1")
public class ConcurrentResource {

    @GET
    @Suspend(resumeOnBroadcast = true, listeners = {SuspendListener.class}, scope = Suspend.SCOPE.REQUEST)
    public Broadcastable subscribe() {
        return new Broadcastable(BroadcasterFactory.getDefault().get(UUID.randomUUID().toString()));
    }

    public final static class SuspendListener extends AtmosphereResourceEventListenerBase {
        @Override
        public void onSuspend(AtmosphereResourceEvent event){
            event.getResource().getBroadcaster().broadcast("foo");
        }
    }

}