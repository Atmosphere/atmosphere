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

import com.sun.jersey.spi.resource.Singleton;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.jboss.netty.handler.codec.http.QueryStringEncoder;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import static org.testng.Assert.assertNotNull;

@Path("/{topic}")
@Produces("text/plain;charset=ISO-8859-1")
public class TestResource {

    private
    @PathParam("topic")
    Broadcaster topic;

    @Context
    BroadcasterFactory bf;

    @Context
    AtmosphereResource ar;

    @GET
    @Suspend(period = 5000)
    public String subscribe() {
        assertNotNull(topic.toString());
        assertNotNull(bf.toString());
        assertNotNull(ar.toString());
        return "";
    }

    @GET
    @Path("/a")
    public String queryString(@QueryParam("a") String a, @QueryParam("b") String b) {
        return a + b;
    }
}