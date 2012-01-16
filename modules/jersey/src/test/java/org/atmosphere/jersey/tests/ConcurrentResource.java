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
package org.atmosphere.jersey.tests;

import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterLifeCyclePolicy;
import org.atmosphere.jersey.Broadcastable;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.*;

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

    public final static class SuspendListener extends AtmosphereResourceEventListenerAdapter {
        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            event.getResource().getBroadcaster().setBroadcasterLifeCyclePolicy(
                    new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY_DESTROY).build());
            event.getResource().getBroadcaster().broadcast("foo");
        }
    }

    @Path("idleDestroyPolicy")
    @GET
    @Suspend(resumeOnBroadcast = true, listeners = {DestroyListener.class})
    public Broadcastable suspend(@Context BroadcasterFactory f) {
        Broadcaster b = f.get(UUID.randomUUID().toString());
        b.setBroadcasterLifeCyclePolicy(
                new BroadcasterLifeCyclePolicy.Builder()
                        .policy(IDLE_DESTROY)
                        .idleTime(20, TimeUnit.SECONDS)
                        .build());
        return new Broadcastable(b);
    }

    public final static class DestroyListener extends AtmosphereResourceEventListenerAdapter {
        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            event.getResource().getBroadcaster().broadcast("foo");
        }
    }

    @Path("idleDestroyResumePolicy")
    @GET
    @Suspend(listeners = {ResumeListener.class}, outputComments = false)
    public Broadcastable suspendForever(@Context BroadcasterFactory f) {
        Broadcaster b = f.get(UUID.randomUUID().toString());
        b.setBroadcasterLifeCyclePolicy(
                new BroadcasterLifeCyclePolicy.Builder()
                        .policy(IDLE_RESUME)
                        .idleTime(30, TimeUnit.SECONDS)
                        .build());
        return new Broadcastable(b);
    }

    public final static class ResumeListener extends AtmosphereResourceEventListenerAdapter {
        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            event.getResource().getBroadcaster().broadcast("foo");
        }
    }
}