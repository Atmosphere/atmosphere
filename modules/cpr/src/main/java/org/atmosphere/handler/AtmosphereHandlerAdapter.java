/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.handler;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * An implementation of {@link AtmosphereHandler} that doesn't nothing.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereHandlerAdapter implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereHandlerAdapter.class);

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        logger.trace("onRequest {}", resource.uuid());
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        logger.trace("onRequest {}", event.getResource().uuid());
    }

    @Override
    public void destroy() {

    }
}
