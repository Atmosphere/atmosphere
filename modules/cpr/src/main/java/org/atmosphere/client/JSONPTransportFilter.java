/*
 * Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.client;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.PerRequestBroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.atmosphere.cpr.BroadcastFilter} that add support for jQuery.atmosphere.js JSONP_TRANSPORT support.
 *
 * @deprecated JSONP is now built in supported.
 * @author Jeanfrancois Arcand
 */
public class JSONPTransportFilter implements PerRequestBroadcastFilter {

    private final Logger logger = LoggerFactory.getLogger(JSONPTransportFilter.class);

    @Override
    public BroadcastAction filter(AtmosphereResource r, Object message, Object originalMessage) {
        logger.warn(getClass().getName() + " is deprecated and not required anymore");
        return new BroadcastAction(message);
    }

    @Override
    public BroadcastAction filter(Object originalMessage, Object message) {
        return new BroadcastAction(message);
    }
}
