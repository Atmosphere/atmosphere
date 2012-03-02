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

import com.sun.jersey.spi.resource.Singleton;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

import static org.testng.Assert.assertNotNull;

/**
 * Simple PubSubTest resource that demonstrate many functionality supported by
 * Atmosphere.
 *
 * @author Jeanfrancois Arcand
 */
@Path("/singleton")
@Produces("text/plain;charset=ISO-8859-1")
@Singleton
public class SingletonResource {

    @Context
    Broadcaster b;

    @Context
    BroadcasterFactory bf;

    @Context
    AtmosphereResource ar;

    @GET
    @Suspend(period = 5000, outputComments = false)
    public String subscribe() {
        assertNotNull(b.toString());
        assertNotNull(bf.toString());
        assertNotNull(ar.toString());
        return "singleton";
    }


    @POST
    @Broadcast
    public String publish(@FormParam("message") String message) {
        return message;
    }


}