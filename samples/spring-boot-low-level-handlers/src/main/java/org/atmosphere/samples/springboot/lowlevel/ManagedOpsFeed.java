/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.samples.springboot.lowlevel;

import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.config.service.Ready;
import org.atmosphere.config.service.Resume;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The annotated twin of {@link OpsFeedHandler}. Same feed, same verbs, one layer up.
 *
 * <p>Everything {@code OpsFeedHandler.onRequest} does with a {@code switch} on the HTTP
 * method, the framework does here by dispatching to the annotated method. That is precisely
 * what {@code @ManagedService} sugars: verb routing, suspend/resume bookkeeping, and
 * encoder/decoder plumbing.</p>
 *
 * <p>What you give up by moving up a layer is visible in the sibling: {@code @RoomAuth}
 * cannot work here, because this class is not the registered handler.</p>
 */
@Singleton
@ManagedService(path = "/atmosphere/managed/ops")
public class ManagedOpsFeed {

    private static final Logger logger = LoggerFactory.getLogger(ManagedOpsFeed.class);

    @Ready
    public void onReady(AtmosphereResource r) {
        logger.info("{} connected to the managed ops feed", r.uuid());
    }

    @Get
    public void onGet(AtmosphereResource r) {
        logger.debug("GET on the managed ops feed from {}", r.uuid());
    }

    @Post
    public void onPost(AtmosphereResource r) {
        logger.info("managed ops broadcast from {}", r.uuid());
        r.getBroadcaster().broadcast("posted");
    }

    @Put
    public void onPut(AtmosphereResource r) {
        logger.info("managed ops upsert from {}", r.uuid());
        r.getBroadcaster().broadcast("put");
    }

    @Delete
    public void onDelete(AtmosphereResource r) {
        logger.info("managed ops delete from {}", r.uuid());
        r.getBroadcaster().broadcast("deleted");
    }

    /**
     * Runs and then resumes the connection — the annotated equivalent of calling
     * {@code resource.resume()} by hand at the end of a raw handler branch.
     */
    @Resume
    public void onResume(AtmosphereResource r) {
        logger.info("{} resumed on the managed ops feed", r.uuid());
    }
}
