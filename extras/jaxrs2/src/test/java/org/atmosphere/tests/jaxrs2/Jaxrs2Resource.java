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
package org.atmosphere.tests.jaxrs2;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Suspend;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.ExecutionContext;

@Path("/test")
@Produces("text/plain")
public class Jaxrs2Resource {

    @Context
    private ExecutionContext ctx;

    @GET
    @Suspend
    public String longGet() {
        new Thread() {

            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                ctx.resume("Atmosphere!");
            }
        }.start();
        return "";
    }

    @GET
    @Path("/p")
    public String programmatic() {
        ctx.suspend();
        new Thread() {

            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                ctx.resume("Atmosphere!");
            }
        }.start();
        return "";
    }

    @Suspend(timeOut = 1000)
    @GET
    @Path("/p2")
    public String timeOut() {
        ctx.setResponse("Atmosphere!");
        return "";
    }
}
