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
package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A default implementation of {@link AsyncSupportListener}
 *
 * @author Jeanfrancois Arcand
 */
public class AsyncSupportListenerAdapter implements AsyncSupportListener {

    private final Logger logger = LoggerFactory.getLogger(AsyncSupportListenerAdapter.class);

    @Override
    public void onSuspend(AtmosphereRequest request, AtmosphereResponse response) {
        logger.trace("Suspended resource {} for request {}", request.resource(), request);
    }

    @Override
    public void onResume(AtmosphereRequest request, AtmosphereResponse response) {
        logger.trace("Resume resource {} for request {}", request.resource(), request);
    }

    @Override
    public void onTimeout(AtmosphereRequest request, AtmosphereResponse response) {
        logger.trace("Timeout resource {} for request {}", request.resource(), request);
    }

    @Override
    public void onClose(AtmosphereRequest request, AtmosphereResponse response) {
        logger.trace("Closing resource {} for request {}", request.resource(), request);
    }

    @Override
    public void onDestroyed(AtmosphereRequest request, AtmosphereResponse response) {
        logger.trace("Destroyed resource {} for request {}", request.resource(), request);
    }
}
