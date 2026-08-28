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

import java.io.IOException;

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.room.auth.RoomAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The raw layer. This class <em>is</em> the registered {@code AtmosphereHandler} — there is
 * no annotated POJO and no proxy in front of it. Compare with {@link ManagedOpsFeed}, which
 * answers the same verbs through {@code @ManagedService}.
 *
 * <p><strong>Why {@code @RoomAuth} lives here and cannot live on the managed twin.</strong>
 * {@code RoomProtocolInterceptor.scanAuthorizer} reads {@code @RoomAuth} off the class of the
 * <em>registered</em> handler. For a {@code @ManagedService} or {@code @RoomService} POJO the
 * registered handler is a {@code ManagedAtmosphereHandler} wrapper, so an annotation on the
 * user class is never seen and no authorizer is installed — silently. It resolves only when
 * the user class is itself the handler, which is exactly what
 * {@code @AtmosphereHandlerService} produces.</p>
 *
 * <p><strong>Caveat worth knowing:</strong> {@code scanAuthorizer} stops at the first match,
 * so exactly one {@link org.atmosphere.room.auth.RoomAuthorizer} is installed framework-wide
 * no matter how many handlers declare one.</p>
 */
@AtmosphereHandlerService(path = "/atmosphere/raw/ops")
@RoomAuth(authorizer = OncallRoomAuthorizer.class)
public class OpsFeedHandler extends AbstractReflectorAtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(OpsFeedHandler.class);

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        String method = resource.getRequest().getMethod();

        // At this layer there is no @Get/@Post dispatch — you route the verbs yourself.
        // That is the whole trade: total control, and nothing done for you.
        switch (method) {
            case "GET" -> {
                resource.suspend();
                logger.info("{} suspended on the raw ops feed", resource.uuid());
            }
            case "POST" -> {
                String body = resource.getRequest().getReader().readLine();
                logger.info("raw ops broadcast: {}", body);
                resource.getBroadcaster().broadcast(body == null ? "" : body);
            }
            default -> {
                AtmosphereResponse response = resource.getResponse();
                response.setStatus(405);
                response.getWriter().write("");
            }
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        // Delegate to the reflector base, which handles the write + transport bookkeeping.
        super.onStateChange(event);
    }

    @Override
    public void destroy() {
        logger.info("raw ops feed handler destroyed");
    }
}
