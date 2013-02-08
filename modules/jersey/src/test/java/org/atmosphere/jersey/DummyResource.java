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
package org.atmosphere.jersey;

import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import java.util.concurrent.ExecutionException;

@Path("/")
@Produces("text/plain;charset=ISO-8859-1")
public class DummyResource {

    @GET
    @Path("scope")
    @Suspend(period = 5000, outputComments = false, scope = Suspend.SCOPE.REQUEST, resumeOnBroadcast = true)
    public Broadcastable suspendScopeRequest(@PathParam("topic") Broadcaster b) throws ExecutionException, InterruptedException {
        return new Broadcastable("bar", b);
    }
}