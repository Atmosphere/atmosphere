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

import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

/**
 * Simple PubSubTest resource that demonstrate many functionality supported by
 * Atmosphere.
 *
 * @author Jeanfrancois Arcand
 */
@Path("/perrequest")
@Produces("text/plain;charset=ISO-8859-1")
public class PerRequestResource {

    private static final Logger logger = LoggerFactory.getLogger(PerRequestResource.class);

    @Context
    Broadcaster broadcaster;

    @Context
    BroadcasterFactory broadcasterFactory;

    @Context
    AtmosphereResource resource;

    @GET
    @Suspend(period = 5000, outputComments = false)
    public String subscribe() {
        logger.info("broadcaster: {}", broadcaster);
        logger.info("factory: {}", broadcasterFactory);
        logger.info("resource: {}", resource);
        return "perrequest";
    }


    @POST
    @Broadcast
    public String publish(@FormParam("message") String message) {
        return message;
    }


}